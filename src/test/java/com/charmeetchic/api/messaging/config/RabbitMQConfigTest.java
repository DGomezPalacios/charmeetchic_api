package com.charmeetchic.api.messaging.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica la topología declarada por {@link RabbitMQConfig} usando los valores REALES de application.yml.
 * No necesita RabbitMQ: solo se comprueban los beans (lo que el RabbitAdmin declararía en el broker).
 */
class RabbitMQConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer()) // carga application.yml
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(RabbitMQConfig.class);

    @Test
    void declaraExchangesDirectTopicYDlx() {
        runner.run(ctx -> {
            assertThat(ctx.getBean("inventarioExchange", DirectExchange.class).getName())
                    .isEqualTo("charmeetchic.inventario");
            assertThat(ctx.getBean("notificacionesExchange", TopicExchange.class).getName())
                    .isEqualTo("charmeetchic.notificaciones");
            assertThat(ctx.getBean("deadLetterExchange", DirectExchange.class).getName())
                    .isEqualTo("charmeetchic.dlx");
            assertThat(ctx.getBeansOfType(DirectExchange.class).values()).allMatch(e -> e.isDurable());
        });
    }

    @Test
    void cadaColaPrincipalApuntaASuDlqViaElDlx() {
        runner.run(ctx -> {
            assertDeadLettering(ctx.getBean("stockBajoQueue", Queue.class),
                    "inventario.stock-bajo", "inventario.stock-bajo.dlq");
            assertDeadLettering(ctx.getBean("movimientosQueue", Queue.class),
                    "inventario.movimientos", "inventario.movimientos.dlq");
            assertDeadLettering(ctx.getBean("reportesQueue", Queue.class),
                    "notificaciones.reportes", "notificaciones.reportes.dlq");
            // 3 colas principales + 3 DLQ, todas durables
            assertThat(ctx.getBeansOfType(Queue.class)).hasSize(6);
            assertThat(ctx.getBeansOfType(Queue.class).values()).allMatch(Queue::isDurable);
        });
    }

    @Test
    void bindingsUsanLasRoutingKeysConfiguradas() {
        runner.run(ctx -> {
            assertBinding(ctx.getBean("stockBajoBinding", Binding.class),
                    "charmeetchic.inventario", "inventario.stock-bajo", "inventario.stock-bajo");
            assertBinding(ctx.getBean("movimientosBinding", Binding.class),
                    "charmeetchic.inventario", "inventario.movimientos", "inventario.movimientos");
            assertBinding(ctx.getBean("reportesBinding", Binding.class),
                    "charmeetchic.notificaciones", "notificacion.reporte.*", "notificaciones.reportes");
            assertBinding(ctx.getBean("reportesDeadLetterBinding", Binding.class),
                    "charmeetchic.dlx", "notificaciones.reportes.dlq", "notificaciones.reportes.dlq");
            assertThat(ctx.getBeansOfType(Binding.class)).hasSize(6);
        });
    }

    @Test
    void usaConversorJson() {
        runner.run(ctx -> assertThat(ctx.getBean(MessageConverter.class))
                .isInstanceOf(Jackson2JsonMessageConverter.class));
    }

    @Test
    void nombreDeColaVacioImpideArrancar() {
        runner.withPropertyValues("app.messaging.queues.stock-bajo.name=")
                .run(ctx -> assertThat(ctx).getFailure()
                        .rootCause().hasMessageContaining("el nombre de la cola no puede estar vacío"));
    }

    @Test
    void dlqConElMismoNombreQueLaColaImpideArrancar() {
        runner.withPropertyValues("app.messaging.queues.movimientos.dead-letter-queue=inventario.movimientos")
                .run(ctx -> assertThat(ctx).getFailure()
                        .rootCause().hasMessageContaining("la DLQ debe tener un nombre distinto"));
    }

    private static void assertDeadLettering(Queue queue, String name, String dlq) {
        assertThat(queue.getName()).isEqualTo(name);
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", "charmeetchic.dlx")
                .containsEntry("x-dead-letter-routing-key", dlq);
    }

    private static void assertBinding(Binding binding, String exchange, String routingKey, String queue) {
        assertThat(binding.getExchange()).isEqualTo(exchange);
        assertThat(binding.getRoutingKey()).isEqualTo(routingKey);
        assertThat(binding.getDestination()).isEqualTo(queue);
    }
}
