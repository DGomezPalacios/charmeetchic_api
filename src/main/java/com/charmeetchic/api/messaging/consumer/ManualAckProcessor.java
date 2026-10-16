package com.charmeetchic.api.messaging.consumer;

import com.charmeetchic.api.messaging.config.MessagingProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Procesa un mensaje con <b>ACK manual</b> y decide qué hacer con él. Lo usan todos los consumidores, así la
 * política es la misma en las tres colas y se explica una sola vez:
 *
 * <pre>
 *  ¿Se pudo leer el JSON y pasa Bean Validation? ── no ──► NACK(requeue=false) ──► DLQ   (permanente)
 *        │ sí
 *  ¿El consumidor lo procesó sin error? ────────── sí ──► ACK                           (listo)
 *        │ no
 *  ¿Fue InvalidMessageException? ───────────────── sí ──► NACK(requeue=false) ──► DLQ   (permanente)
 *        │ no (error transitorio)
 *  ¿intento < max-attempts? ─────── sí ──► esperar, republicar copia con x-retry-count+1, ACK del original
 *        │ no
 *        └──────────────────────────────► NACK(requeue=false) ──► DLQ                  (reintentos agotados)
 * </pre>
 *
 * <p><b>Por qué NO {@code basicNack(requeue=true)}:</b> devuelve el mensaje al inicio de la cola sin ningún
 * contador; si el error persiste se reprocesa para siempre (loop infinito que además bloquea la cola).
 *
 * <p><b>Por qué contar con un header propio ({@code x-retry-count}):</b> RabbitMQ no cuenta los reintentos
 * de un NACK con requeue. Al republicar una copia, el contador viaja en el propio mensaje: cada decisión se
 * puede leer en el log y en la consola de RabbitMQ.
 *
 * <p><b>Por qué republicar en el exchange por defecto ("") con routing key = nombre de la cola:</b> así la
 * copia vuelve SOLO a la cola que falló, aunque otras colas estén unidas al mismo exchange original.
 *
 * <p><b>Orden "republicar y luego ACK":</b> si la app cayera entre ambos pasos, el mensaje quedaría duplicado,
 * nunca perdido (entrega "al menos una vez"). Por eso los consumidores toleran duplicados (idempotencia).
 *
 * <p>El JSON se convierte aquí (y no en la firma del {@code @RabbitListener}) a propósito: si Spring fallara al
 * convertir antes de llamar al método, con ACK manual nadie confirmaría ni rechazaría el mensaje y quedaría
 * colgado. Así, TODOS los casos pasan por una decisión explícita.
 */
@Slf4j
@Component
public class ManualAckProcessor {

    /** Header con la cantidad de reintentos ya hechos (ausente = 0, es el envío original). */
    public static final String RETRY_HEADER = "x-retry-count";

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final RabbitTemplate rabbitTemplate;
    private final int maxAttempts;
    private final long backoffMs;

    public ManualAckProcessor(ObjectMapper objectMapper, Validator validator, RabbitTemplate rabbitTemplate,
                              MessagingProperties props) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.rabbitTemplate = rabbitTemplate;
        this.maxAttempts = props.retry().maxAttempts();
        this.backoffMs = props.retry().backoffMs();
    }

    /**
     * Convierte, valida y procesa {@code message}; luego confirma, reintenta o manda a la DLQ.
     * Nunca lanza excepciones: todas las salidas terminan en un ACK o NACK explícito.
     */
    public <T> void process(Message message, Channel channel, Class<T> type, MessageHandler<T> handler) {
        MessageProperties props = message.getMessageProperties();
        long tag = props.getDeliveryTag();
        String queue = props.getConsumerQueue();
        String id = props.getMessageId() != null ? props.getMessageId() : "(sin message_id)";
        int retriesDone = retryCount(props);
        int attempt = retriesDone + 1;

        T payload = null;
        try {
            payload = read(message, type);
            handler.handle(payload, attempt);
        } catch (InvalidMessageException e) {
            log.error("[{}] id={} intento {}/{} -> NACK a DLQ (mensaje inválido, no se reintenta): {}",
                    queue, id, attempt, maxAttempts, e.getMessage());
            deadLetter(channel, tag, queue, id, handler, payload, "mensaje inválido: " + e.getMessage());
            return;
        } catch (Exception e) {
            if (attempt < maxAttempts) {
                log.warn("[{}] id={} intento {}/{} falló por error transitorio ({}: {}) -> se reintentará",
                        queue, id, attempt, maxAttempts, e.getClass().getSimpleName(), e.getMessage());
                retry(message, channel, tag, queue, id, retriesDone + 1, handler, payload);
            } else {
                log.error("[{}] id={} intento {}/{} -> NACK a DLQ (reintentos agotados). Último error:",
                        queue, id, attempt, maxAttempts, e);
                deadLetter(channel, tag, queue, id, handler, payload,
                        "reintentos agotados (" + maxAttempts + "): " + e.getMessage());
            }
            return;
        }

        try {
            channel.basicAck(tag, false);
            log.info("[{}] id={} intento {}/{} -> ACK (procesado)", queue, id, attempt, maxAttempts);
        } catch (IOException e) {
            // El canal se cayó: RabbitMQ volverá a entregar el mensaje (sin ACK) cuando se reconecte.
            log.error("[{}] id={} procesado pero no se pudo enviar el ACK: {}", queue, id, e.getMessage());
        }
    }

    /** JSON -> record + Bean Validation. Cualquier problema aquí es permanente. */
    private <T> T read(Message message, Class<T> type) {
        T payload;
        try {
            payload = objectMapper.readValue(message.getBody(), type);
        } catch (JsonProcessingException e) {
            throw new InvalidMessageException("JSON ilegible para " + type.getSimpleName() + ": "
                    + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new InvalidMessageException("no se pudo leer el cuerpo del mensaje: " + e.getMessage(), e);
        }
        if (payload == null) {
            throw new InvalidMessageException("el mensaje está vacío");
        }
        Set<ConstraintViolation<T>> violations = validator.validate(payload);
        if (!violations.isEmpty()) {
            String detail = violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .sorted(Comparator.naturalOrder())
                    .collect(Collectors.joining("; "));
            throw new InvalidMessageException("no pasa la validación -> " + detail);
        }
        return payload;
    }

    /** Espera, republica una copia con el contador incrementado y confirma el original. */
    private <T> void retry(Message message, Channel channel, long tag, String queue, String id, int nextRetryCount,
                           MessageHandler<T> handler, T payload) {
        long wait = backoffMs * nextRetryCount;
        if (wait > 0) {
            try {
                Thread.sleep(wait); // espera simple: este proyecto procesa pocos mensajes por segundo
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        MessageProperties props = message.getMessageProperties();
        props.setHeader(RETRY_HEADER, nextRetryCount);
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        try {
            rabbitTemplate.send("", queue, message);
            channel.basicAck(tag, false);
            log.info("[{}] id={} republicado como intento {}/{} (espera {} ms); original confirmado con ACK",
                    queue, id, nextRetryCount + 1, maxAttempts, wait);
        } catch (AmqpException | IOException e) {
            // Sin poder republicar no hay forma segura de reintentar: a la DLQ antes que perderlo o hacer un loop.
            log.error("[{}] id={} no se pudo republicar para reintento ({}) -> NACK a DLQ", queue, id, e.getMessage());
            deadLetter(channel, tag, queue, id, handler, payload, "no se pudo republicar: " + e.getMessage());
        }
    }

    /** Avisa al consumidor y hace basicNack(requeue=false): RabbitMQ lo envía al DLX y de ahí a la DLQ. */
    private <T> void deadLetter(Channel channel, long tag, String queue, String id, MessageHandler<T> handler,
                                T payload, String reason) {
        try {
            handler.onDeadLetter(payload, reason);
        } catch (RuntimeException e) {
            log.error("[{}] id={} error al registrar el fallo antes de la DLQ: {}", queue, id, e.getMessage());
        }
        try {
            channel.basicNack(tag, false, false);
        } catch (IOException e) {
            log.error("[{}] id={} no se pudo enviar el NACK: {}", queue, id, e.getMessage());
        }
    }

    private static int retryCount(MessageProperties props) {
        Object value = props.getHeaders().get(RETRY_HEADER);
        if (value instanceof Number n) {
            return Math.max(0, n.intValue());
        }
        if (value != null) {
            try {
                return Math.max(0, Integer.parseInt(value.toString()));
            } catch (NumberFormatException ignored) {
                // header corrupto: se trata como envío original
            }
        }
        return 0;
    }
}
