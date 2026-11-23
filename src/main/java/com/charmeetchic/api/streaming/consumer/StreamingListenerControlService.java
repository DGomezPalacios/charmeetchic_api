package com.charmeetchic.api.streaming.consumer;

import com.charmeetchic.api.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pausa y reanuda los {@code @KafkaListener} de esta API por su id (demo de LAG), y muestra qué particiones
 * tiene asignadas cada consumidor de cada grupo.
 *
 * <p><b>Pausar en Kafka no es lo mismo que en RabbitMQ.</b> En RabbitMQ se detiene el contenedor y los mensajes
 * se acumulan en la cola. Aquí se usa {@code pause()}: el consumidor SIGUE en su grupo (no hay rebalanceo ni
 * se reparten sus particiones a otro) pero deja de pedir mensajes. Los mensajes nuevos siguen escribiéndose en el
 * log, el offset confirmado del grupo se queda quieto y la diferencia (el LAG) crece. Al reanudar, el consumidor
 * sigue desde su offset y el lag baja hasta 0. Nada se pierde: el mensaje no se borra al leerlo.
 */
@Slf4j
@Service
public class StreamingListenerControlService {

    private final KafkaListenerEndpointRegistry registry;

    public StreamingListenerControlService(KafkaListenerEndpointRegistry registry) {
        this.registry = registry;
    }

    /**
     * Estado de un listener.
     *
     * @param id            id del listener (se usa en la URL de pausa/reanudación)
     * @param grupo         consumer group
     * @param topicos       tópicos que escucha
     * @param corriendo     el contenedor está iniciado
     * @param pausado       se pidió la pausa (se hace efectiva en el siguiente poll)
     * @param concurrencia  consumidores (hilos) que abre en el grupo
     * @param asignaciones  particiones asignadas a cada consumidor (client-id -> particiones)
     */
    public record ListenerStatus(String id, String grupo, List<String> topicos, boolean corriendo, boolean pausado,
                                 int concurrencia, Map<String, List<String>> asignaciones) {
    }

    public List<ListenerStatus> list() {
        return registry.getListenerContainerIds().stream()
                .sorted(Comparator.naturalOrder())
                .map(this::status)
                .toList();
    }

    public ListenerStatus pause(String id) {
        find(id).pause();
        log.warn("Kafka: listener '{}' PAUSADO. Sigue en su grupo pero no consume: su lag crecerá", id);
        return status(id);
    }

    public ListenerStatus resume(String id) {
        find(id).resume();
        log.info("Kafka: listener '{}' REANUDADO. Procesará lo pendiente desde su último offset", id);
        return status(id);
    }

    private ListenerStatus status(String id) {
        MessageListenerContainer container = find(id);
        String[] topics = container.getContainerProperties().getTopics();
        int concurrency = container instanceof ConcurrentMessageListenerContainer<?, ?> c ? c.getConcurrency() : 1;
        Map<String, List<String>> asignaciones = new TreeMap<>();
        Map<String, Collection<TopicPartition>> byClient = container.getAssignmentsByClientId();
        if (byClient != null) {
            byClient.forEach((client, partitions) -> asignaciones.put(client, partitions.stream()
                    .map(tp -> tp.topic() + "-" + tp.partition())
                    .sorted()
                    .toList()));
        }
        return new ListenerStatus(id, container.getGroupId(), topics == null ? List.of() : Arrays.asList(topics),
                container.isRunning(), container.isPauseRequested(), concurrency, asignaciones);
    }

    private MessageListenerContainer find(String id) {
        MessageListenerContainer container = registry.getListenerContainer(id);
        if (container == null) {
            throw new ResourceNotFoundException("No existe el listener de Kafka '" + id + "'. Listeners: "
                    + String.join(", ", registry.getListenerContainerIds()));
        }
        return container;
    }
}
