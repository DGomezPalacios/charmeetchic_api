package com.charmeetchic.rabbitadmin.service;

import com.charmeetchic.rabbitadmin.client.RabbitManagementClient;
import com.charmeetchic.rabbitadmin.client.RabbitManagementClient.NodeDetails;
import com.charmeetchic.rabbitadmin.client.RabbitManagementClient.QueueDetails;
import com.charmeetchic.rabbitadmin.config.AppProperties;
import com.charmeetchic.rabbitadmin.dto.AlertInfo.Alert;
import com.charmeetchic.rabbitadmin.dto.AlertInfo.Severity;
import com.charmeetchic.rabbitadmin.dto.AlertInfo.Snapshot;
import com.charmeetchic.rabbitadmin.dto.AlertInfo.Type;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Reglas de alerta con datos de ejemplo (sin RabbitMQ). */
class AlertServiceTest {

    private static final NodeDetails NODE_OK = new NodeDetails("rabbit@rabbitmq1", true, false, false,
            100L << 20, 320L << 20, 10_000L << 20, 1_000L << 20);

    private final RabbitManagementClient management = mock(RabbitManagementClient.class);
    private final AlertService service = new AlertService(management, new AppProperties(
            new AppProperties.Rabbitmq("http://x"), new AppProperties.Admin(List.of("charmeetchic.")),
            new AppProperties.Security("p", "http://jwks"), new AppProperties.Alerts(true, 30_000, 80, ".dlq")));

    @Test
    void sinProblemas_noHayAlertas() {
        assertThat(service.evaluate(List.of(queue("inventario.movimientos", 0, 1, 10_000),
                queue("inventario.movimientos.dlq", 0, 0, 10_000)), List.of(NODE_OK), Instant.now())).isEmpty();
    }

    @Test
    void dlqConMensajes_esCritica() {
        List<Alert> alerts = service.evaluate(List.of(queue("notificaciones.reportes.dlq", 2, 0, 10_000)),
                List.of(NODE_OK), Instant.now());

        assertThat(alerts).singleElement().satisfies(a -> {
            assertThat(a.type()).isEqualTo(Type.DLQ_CON_MENSAJES);
            assertThat(a.severity()).isEqualTo(Severity.CRITICA);
            assertThat(a.message()).contains("2 mensaje(s)");
        });
    }

    @Test
    void colaSobreElUmbral_esAdvertencia_yLlena_esCritica() {
        List<Alert> alerts = service.evaluate(List.of(
                queue("ventas.metricas", 850, 1, 1000),      // 85 %
                queue("inventario.stock-bajo", 1000, 1, 1000)), // 100 %
                List.of(NODE_OK), Instant.now());

        assertThat(alerts).extracting(a -> a.resource() + ":" + a.severity())
                .containsExactlyInAnyOrder("ventas.metricas:ADVERTENCIA", "inventario.stock-bajo:CRITICA");
    }

    @Test
    void colaConMensajesYSinConsumidores_esAdvertencia() {
        assertThat(service.evaluate(List.of(queue("inventario.movimientos", 3, 0, 10_000)), List.of(NODE_OK),
                Instant.now())).singleElement().satisfies(a -> assertThat(a.type()).isEqualTo(Type.COLA_SIN_CONSUMIDORES));
    }

    @Test
    void nodoCaidoYAlarmasDeMemoriaYDisco() {
        NodeDetails down = new NodeDetails("rabbit@rabbitmq2", false, false, false, null, null, null, null);
        NodeDetails alarmed = new NodeDetails("rabbit@rabbitmq1", true, true, true, 400L << 20, 320L << 20,
                500L << 20, 1_000L << 20);

        assertThat(service.evaluate(List.of(), List.of(down, alarmed), Instant.now()))
                .extracting(Alert::type)
                .containsExactlyInAnyOrder(Type.NODO_CAIDO, Type.ALARMA_MEMORIA, Type.ALARMA_DISCO);
    }

    @Test
    void refresh_conservaElDesdeYLimpiaLasResueltas() {
        when(management.listNodes()).thenReturn(List.of(NODE_OK));
        when(management.listQueues()).thenReturn(List.of(queue("inventario.movimientos.dlq", 1, 0, 10_000)));

        Snapshot first = service.refresh();
        Instant since = first.alerts().get(0).since();
        Snapshot second = service.refresh();
        assertThat(second.alerts().get(0).since()).isEqualTo(since); // la misma alerta, no una nueva

        when(management.listQueues()).thenReturn(List.of(queue("inventario.movimientos.dlq", 0, 0, 10_000)));
        Snapshot resolved = service.refresh();
        assertThat(resolved.ok()).isTrue();
        assertThat(resolved.total()).isZero();
    }

    @Test
    void apiDeAdministracionInalcanzable_esUnaAlerta() {
        when(management.listQueues()).thenThrow(new ResourceAccessException("Connection refused"));

        assertThat(service.refresh().alerts()).singleElement()
                .satisfies(a -> assertThat(a.type()).isEqualTo(Type.BROKER_INALCANZABLE));
    }

    private static QueueDetails queue(String name, int messages, int consumers, int maxLength) {
        return new QueueDetails(name, true, false, Map.of("x-max-length", maxLength), messages, consumers);
    }
}
