package com.charmeetchic.api.messaging.consumer;

import com.charmeetchic.api.enums.MovementType;
import com.charmeetchic.api.messaging.config.MessagingProperties;
import com.charmeetchic.api.messaging.event.StockMovementEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rabbitmq.client.Channel;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.TransientDataAccessResourceException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Política ACK / reintento / DLQ de {@link ManualAckProcessor}, con el Channel de RabbitMQ simulado.
 * "DLQ" = {@code basicNack(tag, false, false)}: RabbitMQ lo reenvía al DLX por los argumentos de la cola.
 */
class ManualAckProcessorTest {

    private static final long TAG = 7L;
    private static final String QUEUE = "inventario.movimientos";
    private static final int MAX_ATTEMPTS = 3;

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private Channel channel;
    private RabbitTemplate rabbitTemplate;
    private ManualAckProcessor processor;

    @BeforeEach
    void setUp() {
        channel = mock(Channel.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        MessagingProperties props = new MessagingProperties(true,
                new MessagingProperties.Retry(MAX_ATTEMPTS, 0), new MessagingProperties.Demo(true), null, null);
        processor = new ManualAckProcessor(objectMapper,
                Validation.buildDefaultValidatorFactory().getValidator(), rabbitTemplate, props);
    }

    @Test
    void mensajeValidoProcesado_haceAck() throws Exception {
        AtomicReference<StockMovementEvent> received = new AtomicReference<>();

        processor.process(message(validEventJson(), null), channel, StockMovementEvent.class,
                (event, attempt) -> received.set(event));

        assertThat(received.get().sku()).isEqualTo("COL-PER-001");
        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    void jsonIlegible_vaDirectoALaDlqSinReintentar() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<String> deadLetterReasons = new ArrayList<>();

        processor.process(message("esto no es json", null), channel, StockMovementEvent.class,
                handler(calls, deadLetterReasons, null));

        assertThat(calls).hasValue(0); // ni siquiera se llamó a la lógica
        verify(channel).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
        assertThat(deadLetterReasons).singleElement().asString().contains("JSON ilegible");
    }

    @Test
    void mensajeQueNoPasaBeanValidation_vaDirectoALaDlq() throws Exception {
        // falta sku y el movimiento no cuadra (10 + -2 != 9)
        String invalid = """
                {"eventId":"e-1","productId":1,"type":"VENTA","quantity":-2,"previousStock":10,
                 "newStock":9,"occurredAt":"2026-10-16T12:00:00Z"}""";
        List<String> deadLetterReasons = new ArrayList<>();

        processor.process(message(invalid, null), channel, StockMovementEvent.class,
                handler(new AtomicInteger(), deadLetterReasons, null));

        verify(channel).basicNack(TAG, false, false);
        assertThat(deadLetterReasons).singleElement().asString()
                .contains("sku: sku es obligatorio")
                .contains("el movimiento no cuadra");
    }

    @Test
    void invalidMessageExceptionDelConsumidor_vaDirectoALaDlq() throws Exception {
        processor.process(message(validEventJson(), null), channel, StockMovementEvent.class,
                handler(new AtomicInteger(), new ArrayList<>(), new InvalidMessageException("no existe")));

        verify(channel).basicNack(TAG, false, false);
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    void errorTransitorio_republicaConContadorYConfirmaElOriginal() throws Exception {
        processor.process(message(validEventJson(), null), channel, StockMovementEvent.class,
                handler(new AtomicInteger(), new ArrayList<>(), new TransientDataAccessResourceException("BD caída")));

        ArgumentCaptor<Message> republished = ArgumentCaptor.forClass(Message.class);
        // exchange por defecto ("") + routing key = nombre de la cola: vuelve SOLO a esta cola
        verify(rabbitTemplate).send(eq(""), eq(QUEUE), republished.capture());
        assertThat((Integer) republished.getValue().getMessageProperties().getHeader(ManualAckProcessor.RETRY_HEADER))
                .isEqualTo(1);
        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void segundoReintento_incrementaElContador() throws Exception {
        processor.process(message(validEventJson(), 1), channel, StockMovementEvent.class,
                handler(new AtomicInteger(), new ArrayList<>(), new TransientDataAccessResourceException("BD caída")));

        ArgumentCaptor<Message> republished = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq(""), eq(QUEUE), republished.capture());
        assertThat((Integer) republished.getValue().getMessageProperties().getHeader(ManualAckProcessor.RETRY_HEADER))
                .isEqualTo(2);
    }

    @Test
    void errorTransitorioEnElUltimoIntento_vaALaDlq() throws Exception {
        AtomicInteger attemptSeen = new AtomicInteger();
        List<String> deadLetterReasons = new ArrayList<>();

        // x-retry-count=2 -> este es el intento 3 de 3
        processor.process(message(validEventJson(), 2), channel, StockMovementEvent.class,
                new MessageHandler<>() {
                    @Override
                    public void handle(StockMovementEvent payload, int attempt) {
                        attemptSeen.set(attempt);
                        throw new TransientDataAccessResourceException("BD caída");
                    }

                    @Override
                    public void onDeadLetter(StockMovementEvent payload, String reason) {
                        deadLetterReasons.add(reason);
                    }
                });

        assertThat(attemptSeen).hasValue(3);
        verify(channel).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
        assertThat(deadLetterReasons).singleElement().asString().startsWith("reintentos agotados (3)");
    }

    @Test
    void siNoSePuedeRepublicar_vaALaDlqEnVezDeHacerLoop() throws Exception {
        doThrow(new AmqpConnectException(new java.net.ConnectException("sin broker")))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

        processor.process(message(validEventJson(), null), channel, StockMovementEvent.class,
                handler(new AtomicInteger(), new ArrayList<>(), new TransientDataAccessResourceException("BD caída")));

        verify(channel).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void nuncaUsaNackConRequeue() throws Exception {
        processor.process(message(validEventJson(), 2), channel, StockMovementEvent.class,
                handler(new AtomicInteger(), new ArrayList<>(), new RuntimeException("cualquier error")));

        verify(channel, never()).basicNack(anyLong(), anyBoolean(), eq(true));
        verify(channel, never()).basicReject(anyLong(), eq(true));
    }

    // ------------------------------------------------------------------ helpers

    private static MessageHandler<StockMovementEvent> handler(AtomicInteger calls, List<String> deadLetterReasons,
                                                              RuntimeException toThrow) {
        return new MessageHandler<>() {
            @Override
            public void handle(StockMovementEvent payload, int attempt) {
                calls.incrementAndGet();
                if (toThrow != null) {
                    throw toThrow;
                }
            }

            @Override
            public void onDeadLetter(StockMovementEvent payload, String reason) {
                deadLetterReasons.add(reason);
            }
        };
    }

    private String validEventJson() throws Exception {
        return objectMapper.writeValueAsString(new StockMovementEvent("e-1", 1L, "COL-PER-001", "Collar",
                MovementType.VENTA, -2, 10, 8, "orden #1", Instant.parse("2026-10-16T12:00:00Z")));
    }

    private static Message message(String body, Integer retryCount) {
        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(TAG);
        props.setConsumerQueue(QUEUE);
        props.setMessageId("e-1");
        if (retryCount != null) {
            props.setHeader(ManualAckProcessor.RETRY_HEADER, retryCount);
        }
        return new Message(body.getBytes(StandardCharsets.UTF_8), props);
    }
}
