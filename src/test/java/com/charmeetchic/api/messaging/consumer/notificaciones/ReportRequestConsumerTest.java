package com.charmeetchic.api.messaging.consumer.notificaciones;

import com.charmeetchic.api.enums.SimulatedFailure;
import com.charmeetchic.api.exception.ResourceNotFoundException;
import com.charmeetchic.api.messaging.config.MessagingProperties;
import com.charmeetchic.api.messaging.consumer.ManualAckProcessor;
import com.charmeetchic.api.messaging.event.ReportRequestedEvent;
import com.charmeetchic.api.service.AsyncReportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rabbitmq.client.Channel;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Consumidor de reportes: camino feliz, fallos simulados de la demo y marcado FALLIDO antes de la DLQ. */
class ReportRequestConsumerTest {

    private static final long TAG = 3L;
    private static final String REPORT_ID = "11111111-1111-1111-1111-111111111111";

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final Channel channel = mock(Channel.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final AsyncReportService reportService = mock(AsyncReportService.class);

    private ReportRequestConsumer consumer(boolean simulatedFailuresEnabled) {
        MessagingProperties props = new MessagingProperties(true, new MessagingProperties.Retry(3, 0),
                new MessagingProperties.Demo(simulatedFailuresEnabled), null, null);
        ManualAckProcessor processor = new ManualAckProcessor(objectMapper,
                Validation.buildDefaultValidatorFactory().getValidator(), rabbitTemplate, props);
        return new ReportRequestConsumer(processor, reportService, props);
    }

    @Test
    void sinFalloSimulado_generaElReporteYHaceAck() throws Exception {
        consumer(true).onReportRequested(message(null, null), channel);

        verify(reportService).generate(REPORT_ID);
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void transitorio_seReintentaMientrasQuedenIntentos() throws Exception {
        consumer(true).onReportRequested(message(SimulatedFailure.TRANSITORIO, null), channel);

        verify(reportService, never()).generate(anyString());
        verify(rabbitTemplate).send(eq(""), eq("notificaciones.reportes"), any(Message.class));
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void transitorio_enElUltimoIntento_marcaFallidoYVaALaDlq() throws Exception {
        consumer(true).onReportRequested(message(SimulatedFailure.TRANSITORIO, 2), channel);

        verify(reportService).markFailed(eq(REPORT_ID), contains("reintentos agotados"));
        verify(channel).basicNack(TAG, false, false);
    }

    @Test
    void transitorioUnaVez_elReintentoSeProcesaBien() throws Exception {
        consumer(true).onReportRequested(message(SimulatedFailure.TRANSITORIO_UNA_VEZ, 1), channel);

        verify(reportService).generate(REPORT_ID);
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void invalido_vaDirectoALaDlqSinReintentar() throws Exception {
        consumer(true).onReportRequested(message(SimulatedFailure.INVALIDO, null), channel);

        verify(reportService).markFailed(eq(REPORT_ID), contains("mensaje inválido"));
        verify(channel).basicNack(TAG, false, false);
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    void reporteInexistente_esMensajeInvalido() throws Exception {
        doThrow(new ResourceNotFoundException("Reporte", REPORT_ID)).when(reportService).generate(REPORT_ID);

        consumer(true).onReportRequested(message(null, null), channel);

        verify(channel).basicNack(TAG, false, false);
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    void fallosSimuladosDeshabilitados_seIgnoraElFlag() throws Exception {
        consumer(false).onReportRequested(message(SimulatedFailure.INVALIDO, null), channel);

        verify(reportService).generate(REPORT_ID);
        verify(channel).basicAck(TAG, false);
    }

    private Message message(SimulatedFailure failure, Integer retryCount) throws Exception {
        ReportRequestedEvent event = new ReportRequestedEvent(REPORT_ID, "inventario", "admin-1",
                Instant.parse("2026-10-16T12:00:00Z"), failure);
        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(TAG);
        props.setConsumerQueue("notificaciones.reportes");
        props.setMessageId(REPORT_ID);
        if (retryCount != null) {
            props.setHeader(ManualAckProcessor.RETRY_HEADER, retryCount);
        }
        return new Message(objectMapper.writeValueAsBytes(event), props);
    }
}
