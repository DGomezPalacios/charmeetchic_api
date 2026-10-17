package com.charmeetchic.api.messaging.config;

import com.charmeetchic.api.messaging.config.MessagingProperties.QueueProperties;
import com.charmeetchic.api.messaging.config.MessagingProperties.SubscriberQueueProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.FanoutExchange;
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
 * Topología de RabbitMQ: exchanges, colas, DLQ, bindings y políticas de retención de cada caso de uso.
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
 * <pre>
 *  [charmeetchic.inventario] (direct)
 *      ├─ inventario.stock-bajo     ──► inventario.stock-bajo     ─NACK/TTL/lleno─┐   asíncrono
 *      ├─ inventario.movimientos    ──► inventario.movimientos    ─NACK/lleno────┤   asíncrono
 *      └─ inventario.consulta-stock ──► inventario.consulta-stock (RPC, sin DLQ)  │   SÍNCRONO
 *  [charmeetchic.notificaciones] (topic)                                          │
 *      └─ notificacion.reporte.*    ──► notificaciones.reportes   ─NACK/lleno────┤   asíncrono
 *  [charmeetchic.ventas] (fanout) ──┬► ventas.auditoria           ─NACK/lleno────┤   publish/subscribe
 *                                   └► ventas.metricas            ─NACK/lleno────┤
 *                                                                                 ▼
 *  [charmeetchic.dlx] (direct) ──► una DLQ por cola (&lt;cola&gt;.dlq), con TTL de 7 días y tope de tamaño
 * </pre>
 *
 * <p><b>Dead Letter:</b> cada cola principal tiene {@code x-dead-letter-exchange} = DLX y
 * {@code x-dead-letter-routing-key} = nombre de SU DLQ. RabbitMQ manda al DLX los mensajes que un consumidor
 * rechaza sin reencolar (NACK requeue=false), los que vencen ({@code x-message-ttl}) y los que se descartan
 * porque la cola se llenó ({@code x-max-length} + {@code x-overflow=drop-head}). Un solo DLX direct basta
 * porque cada cola usa una routing key distinta.
 *
 * <p><b>Retención</b> (valores en {@code app.messaging.retention} y en cada cola del yml): se aplica como
 * ARGUMENTOS de la cola. Ventaja: queda versionada en el código y se ve en la consola. Límite: RabbitMQ no
 * permite cambiar los argumentos de una cola existente (PRECONDITION_FAILED); para cambiarlos hay que borrar
 * la cola y dejar que la app la vuelva a declarar (ver README, "Retención").
 *
 * <p><b>Durabilidad:</b> todas las colas y exchanges son durables (sobreviven a un reinicio del broker) y los
 * mensajes se publican persistentes (lo hace {@code RabbitTemplate} por defecto).
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
public class RabbitMQConfig {

    private final MessagingProperties props;

    public RabbitMQConfig(MessagingProperties props) {
        this.props = props;
        var e = props.exchanges();
        var q = props.queues();
        log.info("Topología RabbitMQ: exchanges [{} (direct), {} (topic), {} (fanout), {} (DLX)], colas [{}, {}, {}, "
                        + "{} (RPC), {}, {}], overflow={}", e.inventario(), e.notificaciones(), e.ventas(), e.deadLetter(),
                q.stockBajo().name(), q.movimientos().name(), q.reportes().name(), q.consultaStock().name(),
                q.ventasAuditoria().name(), q.ventasMetricas().name(), props.retention().overflow());
    }

    // =====================================================================================
    // Conversión de mensajes
    // =====================================================================================

    /**
     * Serializa los mensajes que PUBLICAMOS como JSON (en vez de serialización Java binaria).
     *
     * <ul>
     *   <li>Se lee en la consola de RabbitMQ: útil para la demo y para depurar.</li>
     *   <li>Usa el mismo {@link ObjectMapper} de Spring que los endpoints REST (fechas ISO-8601, etc.).</li>
     *   <li>Al leer (respuesta del RPC) solo confía en las clases del paquete {@code messaging.event}.</li>
     * </ul>
     *
     * <p>Spring Boot detecta este bean y lo inyecta en el {@code RabbitTemplate}.
     */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper, "com.charmeetchic.api.messaging.event");
    }

    // =====================================================================================
    // Exchanges
    // =====================================================================================

    /** Exchange direct de inventario: enruta por coincidencia EXACTA de routing key. */
    @Bean
    public DirectExchange inventarioExchange() {
        return ExchangeBuilder.directExchange(props.exchanges().inventario()).durable(true).build();
    }

    /** Exchange topic de notificaciones: enruta por patrón (notificacion.reporte.*). */
    @Bean
    public TopicExchange notificacionesExchange() {
        return ExchangeBuilder.topicExchange(props.exchanges().notificaciones()).durable(true).build();
    }

    /** Exchange fanout de ventas: entrega una COPIA de cada mensaje a TODAS sus colas (publish/subscribe). */
    @Bean
    public FanoutExchange ventasExchange() {
        return ExchangeBuilder.fanoutExchange(props.exchanges().ventas()).durable(true).build();
    }

    /** Dead Letter Exchange compartido: recibe lo que las colas principales rechazan, vencen o descartan. */
    @Bean
    public DirectExchange deadLetterExchange() {
        return ExchangeBuilder.directExchange(props.exchanges().deadLetter()).durable(true).build();
    }

    // =====================================================================================
    // Caso de uso 1: alerta de stock bajo  (direct, asíncrono)
    // Se publica cuando un producto queda bajo app.inventory.low-stock-threshold.
    // =====================================================================================

    /** Cola de alertas de stock bajo (TTL 24 h: una alerta vieja pasa a la DLQ). */
    @Bean
    public Queue stockBajoQueue() {
        return mainQueue(props.queues().stockBajo());
    }

    /** DLQ de stock bajo: alertas que fallaron, vencieron o se descartaron. */
    @Bean
    public Queue stockBajoDeadLetterQueue() {
        return deadLetterQueue(props.queues().stockBajo().deadLetterQueue());
    }

    @Bean
    public Binding stockBajoBinding(@Qualifier("stockBajoQueue") Queue queue,
                                    @Qualifier("inventarioExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(props.queues().stockBajo().routingKey());
    }

    @Bean
    public Binding stockBajoDeadLetterBinding(@Qualifier("stockBajoDeadLetterQueue") Queue dlq,
                                              @Qualifier("deadLetterExchange") DirectExchange dlx) {
        return BindingBuilder.bind(dlq).to(dlx).with(props.queues().stockBajo().deadLetterQueue());
    }

    // =====================================================================================
    // Caso de uso 2: auditoría de movimientos de stock  (direct, asíncrono)
    // =====================================================================================

    /** Cola de auditoría de movimientos (sin TTL: un registro de auditoría nunca debe vencer). */
    @Bean
    public Queue movimientosQueue() {
        return mainQueue(props.queues().movimientos());
    }

    @Bean
    public Queue movimientosDeadLetterQueue() {
        return deadLetterQueue(props.queues().movimientos().deadLetterQueue());
    }

    @Bean
    public Binding movimientosBinding(@Qualifier("movimientosQueue") Queue queue,
                                      @Qualifier("inventarioExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(props.queues().movimientos().routingKey());
    }

    @Bean
    public Binding movimientosDeadLetterBinding(@Qualifier("movimientosDeadLetterQueue") Queue dlq,
                                                @Qualifier("deadLetterExchange") DirectExchange dlx) {
        return BindingBuilder.bind(dlq).to(dlx).with(props.queues().movimientos().deadLetterQueue());
    }

    // =====================================================================================
    // Caso de uso 3: generación asíncrona de reportes  (topic, asíncrono)
    // =====================================================================================

    @Bean
    public Queue reportesQueue() {
        return mainQueue(props.queues().reportes());
    }

    @Bean
    public Queue reportesDeadLetterQueue() {
        return deadLetterQueue(props.queues().reportes().deadLetterQueue());
    }

    /** Calza con notificacion.reporte.inventario, notificacion.reporte.ventas... (una palabra tras el prefijo). */
    @Bean
    public Binding reportesBinding(@Qualifier("reportesQueue") Queue queue,
                                   @Qualifier("notificacionesExchange") TopicExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(props.queues().reportes().routingKey());
    }

    @Bean
    public Binding reportesDeadLetterBinding(@Qualifier("reportesDeadLetterQueue") Queue dlq,
                                             @Qualifier("deadLetterExchange") DirectExchange dlx) {
        return BindingBuilder.bind(dlq).to(dlx).with(props.queues().reportes().deadLetterQueue());
    }

    // =====================================================================================
    // Caso de uso 4: consulta de stock SÍNCRONA (request-reply / RPC, direct)
    // El cliente publica la consulta y ESPERA la respuesta (direct reply-to) hasta rpc.timeout-ms.
    // =====================================================================================

    /**
     * Cola de consultas RPC. TTL = timeout del cliente: pasado ese tiempo el cliente ya respondió 504 y la
     * consulta no sirve. Sin DLQ a propósito: guardar consultas vencidas solo acumularía basura.
     */
    @Bean
    public Queue consultaStockQueue() {
        var q = props.queues().consultaStock();
        return QueueBuilder.durable(q.name())
                .maxLength(q.maxLength().longValue())
                .overflow(overflow())
                .ttl((int) props.rpc().timeoutMs())
                .build();
    }

    @Bean
    public Binding consultaStockBinding(@Qualifier("consultaStockQueue") Queue queue,
                                        @Qualifier("inventarioExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(props.queues().consultaStock().routingKey());
    }

    // =====================================================================================
    // Caso de uso 5: venta registrada  (fanout, publish/subscribe)
    // UN evento por orden; DOS colas independientes, cada una con su consumidor y su DLQ.
    // =====================================================================================

    /** Suscriptor 1: auditoría de ventas. */
    @Bean
    public Queue ventasAuditoriaQueue() {
        return subscriberQueue(props.queues().ventasAuditoria());
    }

    @Bean
    public Queue ventasAuditoriaDeadLetterQueue() {
        return deadLetterQueue(props.queues().ventasAuditoria().deadLetterQueue());
    }

    /** En fanout no hay routing key: la cola recibe todo lo que llega al exchange. */
    @Bean
    public Binding ventasAuditoriaBinding(@Qualifier("ventasAuditoriaQueue") Queue queue,
                                          @Qualifier("ventasExchange") FanoutExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange);
    }

    @Bean
    public Binding ventasAuditoriaDeadLetterBinding(@Qualifier("ventasAuditoriaDeadLetterQueue") Queue dlq,
                                                    @Qualifier("deadLetterExchange") DirectExchange dlx) {
        return BindingBuilder.bind(dlq).to(dlx).with(props.queues().ventasAuditoria().deadLetterQueue());
    }

    /** Suscriptor 2: métricas de ventas. */
    @Bean
    public Queue ventasMetricasQueue() {
        return subscriberQueue(props.queues().ventasMetricas());
    }

    @Bean
    public Queue ventasMetricasDeadLetterQueue() {
        return deadLetterQueue(props.queues().ventasMetricas().deadLetterQueue());
    }

    @Bean
    public Binding ventasMetricasBinding(@Qualifier("ventasMetricasQueue") Queue queue,
                                         @Qualifier("ventasExchange") FanoutExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange);
    }

    @Bean
    public Binding ventasMetricasDeadLetterBinding(@Qualifier("ventasMetricasDeadLetterQueue") Queue dlq,
                                                   @Qualifier("deadLetterExchange") DirectExchange dlx) {
        return BindingBuilder.bind(dlq).to(dlx).with(props.queues().ventasMetricas().deadLetterQueue());
    }

    // =====================================================================================
    // Constructores de colas (retención aplicada en un solo lugar)
    // =====================================================================================

    private Queue mainQueue(QueueProperties queue) {
        return withDeadLettering(queue.name(), queue.deadLetterQueue(), queue.maxLength(), queue.messageTtlMs());
    }

    private Queue subscriberQueue(SubscriberQueueProperties queue) {
        return withDeadLettering(queue.name(), queue.deadLetterQueue(), queue.maxLength(), queue.messageTtlMs());
    }

    /**
     * Cola principal durable: lo rechazado, vencido o descartado va al DLX con routing key = su DLQ.
     * {@code x-max-length} + {@code x-overflow=drop-head}: si se llena, el mensaje más antiguo va a la DLQ.
     */
    private Queue withDeadLettering(String name, String deadLetterQueue, int maxLength, Long ttlMs) {
        QueueBuilder builder = QueueBuilder.durable(name)
                .deadLetterExchange(props.exchanges().deadLetter())
                .deadLetterRoutingKey(deadLetterQueue)
                .maxLength((long) maxLength)
                .overflow(overflow());
        if (ttlMs != null) {
            builder.ttl(ttlMs.intValue());
        }
        return builder.build();
    }

    /**
     * DLQ durable con retención: TTL largo (7 días por defecto) y tope de tamaño (drop-head: se descartan los
     * más antiguos). No tiene DLX: lo que vence aquí se elimina.
     */
    private Queue deadLetterQueue(String name) {
        return QueueBuilder.durable(name)
                .ttl((int) props.retention().deadLetterTtlMs())
                .maxLength((long) props.retention().deadLetterMaxLength())
                .overflow(QueueBuilder.Overflow.dropHead)
                .build();
    }

    private QueueBuilder.Overflow overflow() {
        return switch (props.retention().overflow()) {
            case "reject-publish" -> QueueBuilder.Overflow.rejectPublish;
            default -> QueueBuilder.Overflow.dropHead;
        };
    }
}
