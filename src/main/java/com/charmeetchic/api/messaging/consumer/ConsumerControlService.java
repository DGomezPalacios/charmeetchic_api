package com.charmeetchic.api.messaging.consumer;

import com.charmeetchic.api.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Pausa y reanuda los consumidores ({@code @RabbitListener}) de ESTA aplicación, por su id
 * (p. ej. {@code consumidor-movimientos}).
 *
 * <p>Sirve para demostrar durabilidad y desacoplamiento: con el consumidor pausado los mensajes se acumulan en
 * la cola (RabbitMQ los guarda, incluso si se reinicia), y al reanudarlo se procesan todos.
 *
 * <p>Pausar = detener el contenedor del listener: cancela la suscripción y devuelve a la cola los mensajes
 * entregados y aún sin ACK. Nada se pierde.
 */
@Slf4j
@Service
public class ConsumerControlService {

    private final RabbitListenerEndpointRegistry registry;

    public ConsumerControlService(RabbitListenerEndpointRegistry registry) {
        this.registry = registry;
    }

    /** Estado de un consumidor: id, si está corriendo y qué colas escucha. */
    public record ConsumerStatus(String id, boolean running, List<String> queues) {
    }

    public List<ConsumerStatus> list() {
        return registry.getListenerContainerIds().stream()
                .sorted(Comparator.naturalOrder())
                .map(this::status)
                .toList();
    }

    public ConsumerStatus pause(String id) {
        MessageListenerContainer container = find(id);
        container.stop();
        log.warn("Consumidor '{}' PAUSADO: los mensajes se acumularán en su cola hasta reanudarlo", id);
        return status(id);
    }

    public ConsumerStatus resume(String id) {
        MessageListenerContainer container = find(id);
        container.start();
        log.info("Consumidor '{}' REANUDADO: procesará los mensajes pendientes", id);
        return status(id);
    }

    private ConsumerStatus status(String id) {
        MessageListenerContainer container = find(id);
        List<String> queues = container instanceof AbstractMessageListenerContainer amlc
                ? Arrays.asList(amlc.getQueueNames())
                : List.of();
        return new ConsumerStatus(id, container.isRunning(), queues);
    }

    private MessageListenerContainer find(String id) {
        MessageListenerContainer container = registry.getListenerContainer(id);
        if (container == null) {
            throw new ResourceNotFoundException("No existe el consumidor '" + id + "'. Consumidores: "
                    + String.join(", ", registry.getListenerContainerIds()));
        }
        return container;
    }
}
