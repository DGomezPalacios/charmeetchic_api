package com.charmeetchic.api.messaging.config;

import com.charmeetchic.api.messaging.config.MessagingProperties.QueueProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topología de RabbitMQ: exchanges, colas, DLQ y bindings de los 3 casos de uso.
 *
 * <p><b>Cómo funciona:</b> esta clase solo DECLARA beans. Spring Boot crea un {@code RabbitAdmin} que, la
 * primera vez que la aplicación abre una conexión con el broker, recorre todos los beans {@link Queue},
 * {@link org.springframework.amqp.core.Exchange} y {@link Binding} y los crea en RabbitMQ si no existen
 * (si existen con los mismos argumentos, no hace nada). Por eso:
 * <ul>
 *   <li>No hay que crear colas a mano en la consola de RabbitMQ.</li>
 *   <li>La app arranca aunque RabbitMQ esté caído (la conexión es perezosa): los tests no lo necesitan.</li>
 * </ul>
 *
 * <p><b>Separación de responsabilidades:</b> aquí no hay lógica de negocio. Los servicios no conocen
 * nombres de colas ni crean objetos AMQP; solo usan los publicadores del paquete {@code messaging.producer}.
 *
 * <p><b>Dead Letter (DLQ):</b> cada cola principal tiene el argumento {@code x-dead-letter-exchange} apuntando
 * al DLX ({@code charmeetchic.dlx}) y {@code x-dead-letter-routing-key} con el nombre de SU DLQ. Cuando un
 * consumidor rechaza un mensaje sin reencolarlo (NACK con requeue=false), RabbitMQ lo reenvía al DLX con
 * esa routing key y el DLX lo deja en la DLQ correspondiente. Un solo DLX de tipo direct basta para las
 * tres colas porque cada una usa una routing key distinta.
 *
 * <pre>
 *  [charmeetchic.inventario] (direct)
 *      ├─ inventario.stock-bajo  ─────────► inventario.stock-bajo   ──NACK──┐
 *      └─ inventario.movimientos ─────────► inventario.movimientos  ──NACK──┤
 *  [charmeetchic.notificaciones] (topic)                                    │
 *      └─ notificacion.reporte.* ─────────► notificaciones.reportes ──NACK──┤
 *                                                                           ▼
 *  [charmeetchic.dlx] (direct) ──► inventario.stock-bajo.dlq / inventario.movimientos.dlq /
 *                                  notificaciones.reportes.dlq
 * </pre>
 *
 * <p>Todas las colas y exchanges son <b>durables</b>: sobreviven a un reinicio del broker. Los mensajes se
 * publican como persistentes (comportamiento por defecto de {@code RabbitTemplate}).
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
public class RabbitMQConfig {

    private final MessagingProperties props;

    public RabbitMQConfig(MessagingProperties props) {
        this.props = props;
        log.info("Topología RabbitMQ: exchanges [{} (direct), {} (topic), {} (DLX)], colas [{}, {}, {}]",
                props.exchanges().inventario(), props.exchanges().notificaciones(), props.exchanges().deadLetter(),
                props.queues().stockBajo().name(), props.queues().movimientos().name(),
                props.queues().reportes().name());
    }

    // =====================================================================================
    // Conversión de mensajes
    // =====================================================================================

    /**
     * Serializa los mensajes como JSON (en vez de serialización Java binaria).
     *
     * <ul>
     *   <li>Se lee en la consola de RabbitMQ: útil para la demo y para depurar.</li>
     *   <li>Usa el mismo {@link ObjectMapper} de Spring que los endpoints REST (fechas ISO-8601, etc.).</li>
     *   <li>Al deserializar solo se confía en clases del paquete {@code messaging}: un mensaje no puede
     *       pedir que se instancie una clase arbitraria del classpath.</li>
     * </ul>
     *
     * <p>Spring Boot detecta este bean y lo inyecta solo en el {@code RabbitTemplate} y en los listeners.
     */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper, "com.charmeetchic.api.messaging");
    }

    // =====================================================================================
    // Exchanges
    // =====================================================================================

    /** Exchange direct de inventario: enruta por coincidencia EXACTA de routing key (stock-bajo, movimientos). */
    @Bean
    public DirectExchange inventarioExchange() {
        return ExchangeBuilder.directExchange(props.exchanges().inventario()).durable(true).build();
    }

    /** Exchange topic de notificaciones: enruta por patrón (notificacion.reporte.*). */
    @Bean
    public TopicExchange notificacionesExchange() {
        return ExchangeBuilder.topicExchange(props.exchanges().notificaciones()).durable(true).build();
    }

    /** Dead Letter Exchange compartido: recibe lo que las colas principales rechazan y lo lleva a cada DLQ. */
    @Bean
    public DirectExchange deadLetterExchange() {
        return ExchangeBuilder.directExchange(props.exchanges().deadLetter()).durable(true).build();
    }

    // =====================================================================================
    // Caso de uso 1: alerta de stock bajo  (exchange direct)
    // Se publica cuando un producto queda bajo app.inventory.low-stock-threshold.
    // =====================================================================================

    /** Cola de alertas de stock bajo. Lo rechazado va a {@code inventario.stock-bajo.dlq}. */
    @Bean
    public Queue stockBajoQueue() {
        return mainQueue(props.queues().stockBajo());
    }

    /** DLQ de stock bajo: alertas que fallaron tras los reintentos, para revisarlas a mano. */
    @Bean
    public Queue stockBajoDeadLetterQueue() {
        return QueueBuilder.durable(props.queues().stockBajo().deadLetterQueue()).build();
    }

    /** inventario exchange --(inventario.stock-bajo)--> cola stock-bajo. */
    @Bean
    public Binding stockBajoBinding(@Qualifier("stockBajoQueue") Queue queue,
                                    @Qualifier("inventarioExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(props.queues().stockBajo().routingKey());
    }

    /** DLX --(inventario.stock-bajo.dlq)--> DLQ de stock bajo. */
    @Bean
    public Binding stockBajoDeadLetterBinding(@Qualifier("stockBajoDeadLetterQueue") Queue dlq,
                                              @Qualifier("deadLetterExchange") DirectExchange dlx) {
        return BindingBuilder.bind(dlq).to(dlx).with(props.queues().stockBajo().deadLetterQueue());
    }

    // =====================================================================================
    // Caso de uso 2: auditoría de movimientos de stock  (exchange direct)
    // Se publica en cada entrada/salida: venta, cancelación de orden, ajuste manual.
    // =====================================================================================

    /** Cola de auditoría de movimientos de stock. Lo rechazado va a {@code inventario.movimientos.dlq}. */
    @Bean
    public Queue movimientosQueue() {
        return mainQueue(props.queues().movimientos());
    }

    /** DLQ de movimientos: registros de auditoría que no se pudieron procesar. */
    @Bean
    public Queue movimientosDeadLetterQueue() {
        return QueueBuilder.durable(props.queues().movimientos().deadLetterQueue()).build();
    }

    /** inventario exchange --(inventario.movimientos)--> cola movimientos. */
    @Bean
    public Binding movimientosBinding(@Qualifier("movimientosQueue") Queue queue,
                                      @Qualifier("inventarioExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(props.queues().movimientos().routingKey());
    }

    /** DLX --(inventario.movimientos.dlq)--> DLQ de movimientos. */
    @Bean
    public Binding movimientosDeadLetterBinding(@Qualifier("movimientosDeadLetterQueue") Queue dlq,
                                                @Qualifier("deadLetterExchange") DirectExchange dlx) {
        return BindingBuilder.bind(dlq).to(dlx).with(props.queues().movimientos().deadLetterQueue());
    }

    // =====================================================================================
    // Caso de uso 3: generación asíncrona de reportes de inventario  (exchange topic)
    // La petición HTTP responde de inmediato; el reporte se arma en segundo plano.
    // =====================================================================================

    /** Cola de solicitudes de reporte. Lo rechazado va a {@code notificaciones.reportes.dlq}. */
    @Bean
    public Queue reportesQueue() {
        return mainQueue(props.queues().reportes());
    }

    /** DLQ de reportes: solicitudes de reporte que fallaron tras los reintentos. */
    @Bean
    public Queue reportesDeadLetterQueue() {
        return QueueBuilder.durable(props.queues().reportes().deadLetterQueue()).build();
    }

    /**
     * notificaciones exchange --(notificacion.reporte.*)--> cola reportes.
     * Calza con notificacion.reporte.inventario, notificacion.reporte.ventas, etc. (exactamente una palabra
     * después del prefijo), así se pueden agregar tipos de reporte sin tocar la topología.
     */
    @Bean
    public Binding reportesBinding(@Qualifier("reportesQueue") Queue queue,
                                   @Qualifier("notificacionesExchange") TopicExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(props.queues().reportes().routingKey());
    }

    /** DLX --(notificaciones.reportes.dlq)--> DLQ de reportes. */
    @Bean
    public Binding reportesDeadLetterBinding(@Qualifier("reportesDeadLetterQueue") Queue dlq,
                                             @Qualifier("deadLetterExchange") DirectExchange dlx) {
        return BindingBuilder.bind(dlq).to(dlx).with(props.queues().reportes().deadLetterQueue());
    }

    // =====================================================================================

    /**
     * Cola principal durable con su dead-lettering configurado: un NACK sin reencolar la manda al DLX con
     * routing key = nombre de su DLQ.
     */
    private Queue mainQueue(QueueProperties queue) {
        return QueueBuilder.durable(queue.name())
                .deadLetterExchange(props.exchanges().deadLetter())
                .deadLetterRoutingKey(queue.deadLetterQueue())
                .build();
    }
}
