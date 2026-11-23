package com.charmeetchic.api.streaming;

import com.charmeetchic.api.enums.MovementType;
import com.charmeetchic.api.service.EventosRecientesService;
import com.charmeetchic.api.service.HistorialVentasService;
import com.charmeetchic.api.service.StockMonitorService;
import com.charmeetchic.api.service.VentasMetricasService;
import com.charmeetchic.api.streaming.config.KafkaConsumerConfig;
import com.charmeetchic.api.streaming.config.StreamingProperties;
import com.charmeetchic.api.streaming.consumer.DeadLetterMonitorListener;
import com.charmeetchic.api.streaming.consumer.HistorialVentasListener;
import com.charmeetchic.api.streaming.consumer.InvalidStreamingEventException;
import com.charmeetchic.api.streaming.consumer.MonitorStockListener;
import com.charmeetchic.api.streaming.consumer.SimulatedStreamingFailureException;
import com.charmeetchic.api.streaming.consumer.StreamingEventValidator;
import com.charmeetchic.api.streaming.consumer.VentasMetricasListener;
import com.charmeetchic.api.streaming.event.StockEvent;
import com.charmeetchic.api.streaming.event.VentaEvent;
import com.charmeetchic.api.streaming.producer.AfterCommitKafkaSender;
import com.charmeetchic.api.streaming.producer.StreamingEventPublisher;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.charmeetchic.api.streaming.StreamingTestData.item;
import static com.charmeetchic.api.streaming.StreamingTestData.props;
import static com.charmeetchic.api.streaming.StreamingTestData.stock;
import static com.charmeetchic.api.streaming.StreamingTestData.venta;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Productor, validación y listeners sin broker: reglas de tópico/key, after-commit y manejo de errores. */
class StreamingInfrastructureTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private final AfterCommitKafkaSender sender = mock(AfterCommitKafkaSender.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, byte[]> bytesTemplate = mock(KafkaTemplate.class);
    private final StreamingEventValidator validator = new StreamingEventValidator(VALIDATOR);

    @AfterEach
    void cleanTx() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ------------------------------------------------------------------ publicador

    @Test
    @SuppressWarnings("unchecked")
    void ventaVaAVentasEventosConKeyOrderIdYStockAInventarioConKeySku() {
        StreamingEventPublisher publisher = new StreamingEventPublisher(sender, bytesTemplate, VALIDATOR, props());

        publisher.publishVenta(venta(VentaEvent.VentaTipo.CREADA, "12", Instant.now(), "100", item("A", 1)));
        publisher.publishStock(stock(StockEvent.StockTipo.VENTA, "SKU-1", 5, 4));

        ArgumentCaptor<ProducerRecord<String, Object>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(sender, org.mockito.Mockito.times(2)).send(captor.capture());
        assertThat(captor.getAllValues().get(0).topic()).isEqualTo("ventas.eventos");
        assertThat(captor.getAllValues().get(0).key()).isEqualTo("12");
        assertThat(captor.getAllValues().get(1).topic()).isEqualTo("inventario.stock");
        assertThat(captor.getAllValues().get(1).key()).isEqualTo("SKU-1");
    }

    @Test
    void noPublicaEventosInvalidosNiConElStreamingApagado() {
        StreamingEventPublisher publisher = new StreamingEventPublisher(sender, bytesTemplate, VALIDATOR, props());
        publisher.publishVenta(new VentaEvent("e", VentaEvent.VentaTipo.CREADA, Instant.now(), "1", "u", List.of(),
                new BigDecimal("-1"), VentaEvent.Origen.REAL));
        publisher.publishStock(stock(StockEvent.StockTipo.VENTA, "S", 4, 4)); // no cambió el stock

        StreamingEventPublisher off = new StreamingEventPublisher(sender, bytesTemplate, VALIDATOR,
                props(false, true, 10));
        off.publishStock(stock(StockEvent.StockTipo.VENTA, "S", 5, 4));

        verifyNoInteractions(sender);
        assertThat(off.isEnabled()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void metodosDeDemoSeSaltanLaValidacionYMarcanElFallo() {
        when(bytesTemplate.send(anyString(), anyString(), any(byte[].class))).thenReturn(new CompletableFuture<>());
        StreamingEventPublisher publisher = new StreamingEventPublisher(sender, bytesTemplate, VALIDATOR, props());

        publisher.publishInvalidVentaForDemo(new VentaEvent("e", VentaEvent.VentaTipo.CREADA, Instant.now(), "1", "u",
                List.of(), new BigDecimal("-1"), VentaEvent.Origen.SIMULACION));
        publisher.publishVentaWithSimulatedFailureForDemo(
                venta(VentaEvent.VentaTipo.CREADA, "2", Instant.now(), "1", item("A", 1)));
        publisher.publishRawForDemo("k", "no es json");

        ArgumentCaptor<ProducerRecord<String, Object>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(sender, org.mockito.Mockito.times(2)).send(captor.capture());
        assertThat(captor.getAllValues().get(1).headers()
                .lastHeader(StreamingEventPublisher.SIMULATED_FAILURE_HEADER)).isNotNull();
        verify(bytesTemplate).send(eq("ventas.eventos"), eq("k"), any(byte[].class));

        // si el envío crudo falla de inmediato (Kafka caído) no se propaga la excepción
        when(bytesTemplate.send(anyString(), anyString(), any(byte[].class))).thenThrow(new IllegalStateException("x"));
        publisher.publishRawForDemo("k", "no es json");
    }

    @Test
    @SuppressWarnings("unchecked")
    void senderEsperaAlCommitSiHayTransaccion() {
        KafkaTemplate<String, Object> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());
        AfterCommitKafkaSender realSender = new AfterCommitKafkaSender(template);
        ProducerRecord<String, Object> rec = new ProducerRecord<>("t", "k", "v");

        TransactionSynchronizationManager.initSynchronization();
        realSender.send(rec);
        verify(template, never()).send(any(ProducerRecord.class));
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(template).send(rec);
        TransactionSynchronizationManager.clearSynchronization();

        // sin transacción se envía de inmediato; un error inmediato (Kafka caído) se registra y no se propaga
        when(template.send(any(ProducerRecord.class))).thenThrow(new IllegalStateException("sin metadatos"));
        realSender.send(rec);
        CompletableFuture<Object> failed = CompletableFuture.failedFuture(new IllegalStateException("timeout"));
        org.mockito.Mockito.doReturn(failed).when(template).send(any(ProducerRecord.class));
        realSender.send(rec);
    }

    // ------------------------------------------------------------------ validación y listeners

    @Test
    void validadorRechazaEventosInvalidosYNulos() {
        assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(InvalidStreamingEventException.class);
        assertThatThrownBy(() -> validator.validate(stock(StockEvent.StockTipo.VENTA, "S", -1, -1)))
                .isInstanceOf(InvalidStreamingEventException.class)
                .satisfies(e -> assertThat(((InvalidStreamingEventException) e).getErrors()).isNotEmpty());
    }

    @Test
    void listenersDeleganEnLosServicios() {
        StreamingProperties props = props();
        EventosRecientesService eventos = new EventosRecientesService(props);
        VentasMetricasService metricas = new VentasMetricasService(eventos, props);
        HistorialVentasService historial = new HistorialVentasService();
        StockMonitorService monitor = new StockMonitorService(eventos, 5);
        VentaEvent v = venta(VentaEvent.VentaTipo.CREADA, "1", Instant.now(), "10", item("A", 1));

        new VentasMetricasListener(metricas, validator, props).onVenta(new ConsumerRecord<>("ventas.eventos", 2, 7, "1", v));
        new HistorialVentasListener(historial, validator).onVenta(new ConsumerRecord<>("ventas.eventos", 2, 7, "1", v));
        MonitorStockListener stockListener = new MonitorStockListener(monitor, validator);
        stockListener.onStock(new ConsumerRecord<>("inventario.stock", 0, 1, "S",
                stock(StockEvent.StockTipo.VENTA, "S", 5, 1)));

        assertThat(metricas.ventasTotales()).isEqualTo(1);
        assertThat(historial.resumen("h").porParticion()).containsEntry(2, 1L);
        assertThat(monitor.stockDe("S")).isEqualTo(1);

        stockListener.onStock(new ConsumerRecord<>("inventario.stock", 0, 2, "S", null)); // tombstone
        assertThat(monitor.stockDe("S")).isNull();
    }

    @Test
    void headerDeFalloSimuladoHaceFallarAlListenerDeMetricas() {
        StreamingProperties props = props();
        VentasMetricasService metricas = new VentasMetricasService(new EventosRecientesService(props), props);
        ConsumerRecord<String, VentaEvent> rec = new ConsumerRecord<>("ventas.eventos", 0, 0, "1",
                venta(VentaEvent.VentaTipo.CREADA, "1", Instant.now(), "10", item("A", 1)));
        rec.headers().add(StreamingEventPublisher.SIMULATED_FAILURE_HEADER, "true".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new VentasMetricasListener(metricas, validator, props).onVenta(rec))
                .isInstanceOf(SimulatedStreamingFailureException.class);
        // con la simulación deshabilitada, el header se ignora
        new VentasMetricasListener(metricas, validator, props(true, false, 10)).onVenta(rec);
        assertThat(metricas.ventasTotales()).isEqualTo(1);
    }

    @Test
    void monitorDltRegistraGrupoYMotivo() {
        EventosRecientesService eventos = new EventosRecientesService(props());
        DeadLetterMonitorListener listener = new DeadLetterMonitorListener(eventos);
        ConsumerRecord<String, String> rec = new ConsumerRecord<>("ventas.eventos.DLT", 1, 3, "SIM-1", "{roto");
        rec.headers().add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP, bytes("metricas-ventas")));
        rec.headers().add(new RecordHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC, bytes("ventas.eventos")));
        rec.headers().add(new RecordHeader(KafkaConsumerConfig.DLT_REASON_HEADER, bytes("JsonParseException: x")));
        listener.onDeadLetter(rec);

        ConsumerRecord<String, String> sinMotivoPropio = new ConsumerRecord<>("inventario.stock.DLT", 0, 0, "S", "x");
        sinMotivoPropio.headers().add(new RecordHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE, bytes("m".repeat(200))));
        listener.onDeadLetter(sinMotivoPropio);

        assertThat(eventos.ultimos()).hasSize(2);
        assertThat(eventos.ultimos().get(1).detalle()).contains("metricas-ventas").contains("JsonParseException");
        assertThat(eventos.ultimos().get(0).detalle()).contains("grupo ?").endsWith("...");
    }

    // ------------------------------------------------------------------ eventos y propiedades

    @Test
    void reglasDeLosEventosYDeLasPropiedades() {
        assertThat(StockEvent.StockTipo.from(MovementType.INGRESO_INICIAL)).isEqualTo(StockEvent.StockTipo.INGRESO_INICIAL);
        assertThat(StockEvent.StockTipo.from(MovementType.CANCELACION_ORDEN)).isEqualTo(StockEvent.StockTipo.CANCELACION);
        assertThat(StockEvent.StockTipo.from(MovementType.AJUSTE_MANUAL)).isEqualTo(StockEvent.StockTipo.AJUSTE);
        assertThat(stock(StockEvent.StockTipo.SINCRONIZACION, "S", 3, 3).isCambioReal()).isTrue();
        assertThat(stock(StockEvent.StockTipo.AJUSTE, "S", 3, 3).isCambioReal()).isFalse();
        assertThat(new StockEvent("e", null, Instant.now(), "S", 1L, "n", 1, 1, null).isCambioReal()).isTrue();
        assertThat(new StockEvent("e", StockEvent.StockTipo.VENTA, Instant.now(), "S", 1L, "n", null, 1, null)
                .isCambioReal()).isTrue();
        assertThat(new VentaEvent("e", VentaEvent.VentaTipo.CREADA, Instant.now(), "1", "u", null, BigDecimal.ONE,
                VentaEvent.Origen.REAL).unidades()).isZero();

        StreamingProperties.Topics topics = props().topics();
        assertThat(topics.deadLetterOf("ventas.eventos")).isEqualTo("ventas.eventos.DLT");
        assertThat(topics.isDeadLetterWideEnough()).isTrue();
        StreamingProperties.Topics angosto = new StreamingProperties.Topics(topics.ventas(), topics.stock(),
                new StreamingProperties.DeadLetterProperties(".DLT", 1, (short) 1, null));
        assertThat(angosto.isDeadLetterWideEnough()).isFalse();
        assertThat(new StreamingProperties.Topics(null, null, null).isDeadLetterWideEnough()).isTrue();
        assertThat(new StreamingProperties.TopicProperties("t", 1, (short) 1, null).configs()).isEmpty();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
