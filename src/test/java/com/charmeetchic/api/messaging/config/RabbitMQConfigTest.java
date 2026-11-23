package com.charmeetchic.api.messaging.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

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
            assertDeadLettering(ctx.getBean("ventasAuditoriaQueue", Queue.class), "ventas.auditoria", "ventas.auditoria.dlq");
            assertDeadLettering(ctx.getBean("ventasMetricasQueue", Queue.class), "ventas.metricas", "ventas.metricas.dlq");
            // 5 colas con DLQ + 5 DLQ + 1 cola RPC, todas durables
            assertThat(ctx.getBeansOfType(Queue.class)).hasSize(11);
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
            // 6 originales + RPC + 2 del fanout + 2 de sus DLQ
            assertThat(ctx.getBeansOfType(Binding.class)).hasSize(11);
        });
    }

    @Test
    void exchangeFanoutDeVentas_entregaADosColasSinRoutingKey() {
        runner.run(ctx -> {
            assertThat(ctx.getBean("ventasExchange", FanoutExchange.class).getName()).isEqualTo("charmeetchic.ventas");
            assertBinding(ctx.getBean("ventasAuditoriaBinding", Binding.class), "charmeetchic.ventas", "", "ventas.auditoria");
            assertBinding(ctx.getBean("ventasMetricasBinding", Binding.class), "charmeetchic.ventas", "", "ventas.metricas");
        });
    }

    @Test
    void colaRpc_tieneTtlIgualAlTimeoutYNoTieneDlx() {
        runner.run(ctx -> {
            Queue rpc = ctx.getBean("consultaStockQueue", Queue.class);
            assertThat(rpc.getName()).isEqualTo("inventario.consulta-stock");
            assertThat(rpc.getArguments())
                    .containsEntry("x-message-ttl", 3000)
                    .containsEntry("x-max-length", 1000L)
                    .doesNotContainKey("x-dead-letter-exchange");
            assertBinding(ctx.getBean("consultaStockBinding", Binding.class),
                    "charmeetchic.inventario", "inventario.consulta-stock", "inventario.consulta-stock");
        });
    }

    @Test
    void retencion_colasPrincipalesConMaxLengthDropHeadYTtlDondeCorresponde() {
        runner.run(ctx -> {
            assertThat(ctx.getBean("stockBajoQueue", Queue.class).getArguments())
                    .containsEntry("x-max-length", 1000L)
                    .containsEntry("x-overflow", "drop-head")
                    .containsEntry("x-message-ttl", 86_400_000);
            // auditoría: con tope pero SIN vencimiento
            assertThat(ctx.getBean("movimientosQueue", Queue.class).getArguments())
                    .containsEntry("x-max-length", 10_000L)
                    .doesNotContainKey("x-message-ttl");
        });
    }

    @Test
    void retencion_dlqConTtlDeSieteDiasYTope() {
        runner.run(ctx -> {
            for (String dlq : List.of("stockBajoDeadLetterQueue", "movimientosDeadLetterQueue", "reportesDeadLetterQueue",
                    "ventasAuditoriaDeadLetterQueue", "ventasMetricasDeadLetterQueue")) {
                assertThat(ctx.getBean(dlq, Queue.class).getArguments()).as(dlq)
                        .containsEntry("x-message-ttl", 604_800_000)
                        .containsEntry("x-max-length", 10_000L)
                        .containsEntry("x-overflow", "drop-head")
                        .doesNotContainKey("x-dead-letter-exchange");
            }
        });
    }

    @Test
    void overflowInvalido_impideArrancar() {
        runner.withPropertyValues("app.messaging.retention.overflow=borrar-todo")
                .run(ctx -> assertThat(ctx).getFailure()
                        .rootCause().hasMessageContaining("retention.overflow debe ser drop-head o reject-publish"));
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
