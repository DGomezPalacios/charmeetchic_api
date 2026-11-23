package com.charmeetchic.kafkaadmin.service;

import com.charmeetchic.kafkaadmin.config.AppProperties;
import com.charmeetchic.kafkaadmin.dto.AdminRequests.CreateTopicRequest;
import com.charmeetchic.kafkaadmin.dto.AdminRequests.ResetOffsetsRequest;
import com.charmeetchic.kafkaadmin.dto.AdminRequests.ResetStrategy;
import com.charmeetchic.kafkaadmin.dto.AdminResponses.GroupDetail;
import com.charmeetchic.kafkaadmin.dto.AdminResponses.GroupSummary;
import com.charmeetchic.kafkaadmin.dto.AdminResponses.MemberDetail;
import com.charmeetchic.kafkaadmin.dto.AdminResponses.OperationResult;
import com.charmeetchic.kafkaadmin.dto.AdminResponses.PartitionDetail;
import com.charmeetchic.kafkaadmin.dto.AdminResponses.PartitionLag;
import com.charmeetchic.kafkaadmin.dto.AdminResponses.TopicDetail;
import com.charmeetchic.kafkaadmin.dto.AdminResponses.TopicSummary;
import com.charmeetchic.kafkaadmin.exception.AdminExceptions.BadRequestException;
import com.charmeetchic.kafkaadmin.exception.AdminExceptions.BrokerUnavailableException;
import com.charmeetchic.kafkaadmin.exception.AdminExceptions.ConflictException;
import com.charmeetchic.kafkaadmin.exception.AdminExceptions.NotFoundException;
import com.charmeetchic.kafkaadmin.exception.AdminExceptions.ProtectedResourceException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.errors.GroupNotEmptyException;
import org.apache.kafka.common.errors.InvalidConfigurationException;
import org.apache.kafka.common.errors.InvalidPartitionsException;
import org.apache.kafka.common.errors.InvalidReplicationFactorException;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.PolicyViolationException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownMemberIdException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Toda la administración de Kafka pasa por aquí: es la ÚNICA clase que usa el {@link Admin} (AdminClient).
 * Los controladores reciben DTOs validados y devuelven DTOs; nunca ven clases del cliente de Kafka.
 *
 * <p>Las operaciones del AdminClient son asíncronas ({@link KafkaFuture}); aquí se esperan con timeout y los
 * errores de Kafka se traducen a excepciones de negocio (404, 409, 400, 503) en {@link #await}.
 *
 * <p><b>Protecciones</b>: los tópicos internos de Kafka ({@code __consumer_offsets}...) no se pueden modificar
 * ni borrar; los tópicos de la API de inventario ({@code app.admin.protected-topics}) no se pueden borrar.
 */
@Slf4j
@Service
public class KafkaAdminService {

    /** Configs que siempre se muestran al describir un tópico, aunque tengan el valor por defecto. */
    private static final Set<String> KEY_CONFIGS = Set.of("cleanup.policy", "retention.ms", "min.insync.replicas");

    private final Admin admin;
    private final AppProperties props;

    public KafkaAdminService(Admin admin, AppProperties props) {
        this.admin = admin;
        this.props = props;
    }

    // ------------------------------------------------------------------ tópicos

    public List<TopicSummary> listTopics(boolean includeInternal) {
        Set<String> names = await(admin.listTopics(new ListTopicsOptions().listInternal(includeInternal)).names(),
                "listar tópicos");
        if (names.isEmpty()) {
            return List.of();
        }
        return await(admin.describeTopics(names).allTopicNames(), "describir tópicos").values().stream()
                .map(d -> new TopicSummary(d.name(), d.partitions().size(), replicationFactor(d), d.isInternal(),
                        isProtected(d.name())))
                .sorted(Comparator.comparing(TopicSummary::name))
                .toList();
    }

    public TopicDetail describeTopic(String name) {
        TopicDescription d = await(admin.describeTopics(List.of(name)).allTopicNames(), "describir " + name).get(name);
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, name);
        Config config = await(admin.describeConfigs(List.of(resource)).all(), "leer configs de " + name).get(resource);
        // Solo las configs fijadas en el tópico (las demás heredan el valor del broker) + las más relevantes.
        Map<String, String> configs = new TreeMap<>();
        for (ConfigEntry e : config.entries()) {
            if (e.source() == ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG || KEY_CONFIGS.contains(e.name())) {
                configs.put(e.name(), e.value());
            }
        }
        List<PartitionDetail> partitions = d.partitions().stream()
                .sorted(Comparator.comparingInt(TopicPartitionInfo::partition))
                .map(p -> new PartitionDetail(p.partition(), p.leader() == null ? null : p.leader().id(),
                        ids(p.replicas()), ids(p.isr()), p.isr().size() == p.replicas().size()))
                .toList();
        return new TopicDetail(name, d.isInternal(), isProtected(name), replicationFactor(d), partitions, configs);
    }

    public TopicDetail createTopic(CreateTopicRequest req) {
        guardInternal(req.name(), "crear");
        if (req.replicationFactor() > props.admin().maxReplicationFactor()) {
            throw new BadRequestException("replicationFactor no puede superar " + props.admin().maxReplicationFactor());
        }
        if (req.partitions() > props.admin().maxPartitions()) {
            throw new BadRequestException("partitions no puede superar " + props.admin().maxPartitions());
        }
        NewTopic topic = new NewTopic(req.name(), req.partitions(), req.replicationFactor());
        if (req.configs() != null) {
            topic.configs(req.configs());
        }
        await(admin.createTopics(List.of(topic)).all(), "crear el tópico " + req.name());
        log.info("Tópico {} creado: {} particiones, RF {}, configs {}", req.name(), req.partitions(),
                req.replicationFactor(), req.configs());
        return describeTopic(req.name());
    }

    /** Cambia (SET) las configs indicadas; las demás no se tocan. */
    public TopicDetail updateConfigs(String name, Map<String, String> configs) {
        guardInternal(name, "modificar");
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, name);
        List<AlterConfigOp> ops = configs.entrySet().stream()
                .map(e -> new AlterConfigOp(new ConfigEntry(e.getKey(), e.getValue().trim()), AlterConfigOp.OpType.SET))
                .toList();
        describeTopic(name); // 404 claro si no existe (incrementalAlterConfigs da un error menos legible)
        await(admin.incrementalAlterConfigs(Map.of(resource, ops)).all(), "modificar configs de " + name);
        log.info("Configs de {} modificadas: {}", name, configs);
        return describeTopic(name);
    }

    /**
     * Aumenta las particiones. Kafka NO permite reducirlas (habría que mover o perder mensajes), y al aumentar
     * cambia a qué partición va cada key: los mensajes nuevos de una key pueden ir a otra partición.
     */
    public TopicDetail increasePartitions(String name, int totalPartitions) {
        guardInternal(name, "modificar");
        int actuales = describeTopic(name).partitions().size();
        if (totalPartitions <= actuales) {
            throw new BadRequestException("El tópico " + name + " ya tiene " + actuales
                    + " particiones; Kafka solo permite aumentarlas");
        }
        if (totalPartitions > props.admin().maxPartitions()) {
            throw new BadRequestException("totalPartitions no puede superar " + props.admin().maxPartitions());
        }
        await(admin.createPartitions(Map.of(name, NewPartitions.increaseTo(totalPartitions))).all(),
                "aumentar particiones de " + name);
        log.info("Tópico {}: particiones {} -> {}", name, actuales, totalPartitions);
        return describeTopic(name);
    }

    public OperationResult deleteTopic(String name) {
        guardInternal(name, "borrar");
        if (isProtected(name)) {
            throw new ProtectedResourceException("El tópico '" + name + "' es de la API de inventario y no se puede "
                    + "borrar desde aquí (app.admin.protected-topics)");
        }
        await(admin.deleteTopics(List.of(name)).all(), "borrar el tópico " + name);
        log.warn("Tópico {} BORRADO", name);
        return new OperationResult(name, "DELETE", "tópico borrado");
    }

    // ------------------------------------------------------------------ consumer groups

    public List<GroupSummary> listGroups() {
        return await(admin.listConsumerGroups().all(), "listar consumer groups").stream()
                .map(g -> new GroupSummary(g.groupId(), g.state().map(Enum::name).orElse("UNKNOWN")))
                .sorted(Comparator.comparing(GroupSummary::groupId))
                .toList();
    }

    /** Estado, miembros y, por partición, offset confirmado, final del log y LAG. */
    public GroupDetail describeGroup(String groupId) {
        ConsumerGroupDescription d = await(admin.describeConsumerGroups(List.of(groupId)).all(),
                "describir el grupo " + groupId).get(groupId);
        Map<TopicPartition, OffsetAndMetadata> committed = committedOffsets(groupId);
        if (d.state() == ConsumerGroupState.DEAD && committed.isEmpty()) {
            throw new NotFoundException("No existe el consumer group '" + groupId + "'");
        }
        List<MemberDetail> members = d.members().stream()
                .map(m -> new MemberDetail(m.clientId(), m.host(), m.assignment().topicPartitions().stream()
                        .map(tp -> tp.topic() + "-" + tp.partition()).sorted().toList()))
                .sorted(Comparator.comparing(MemberDetail::clientId))
                .toList();

        // Particiones a medir: las que el grupo confirmó y las que tiene asignadas (aunque aún no confirme nada).
        Set<TopicPartition> partitions = new java.util.HashSet<>(committed.keySet());
        d.members().forEach(m -> partitions.addAll(m.assignment().topicPartitions()));
        Map<TopicPartition, Long> ends = endOffsets(partitions, OffsetSpec.latest());

        List<PartitionLag> lags = partitions.stream()
                .sorted(Comparator.comparing(TopicPartition::topic).thenComparingInt(TopicPartition::partition))
                .map(tp -> {
                    OffsetAndMetadata c = committed.get(tp);
                    long end = ends.getOrDefault(tp, 0L);
                    Long offset = c == null ? null : c.offset();
                    long lag = offset == null ? end : Math.max(0, end - offset);
                    return new PartitionLag(tp.topic(), tp.partition(), offset, end, lag);
                })
                .toList();
        Node coordinator = d.coordinator();
        return new GroupDetail(groupId, d.state().name(), coordinator == null ? null : coordinator.id(), members, lags,
                lags.stream().mapToLong(PartitionLag::lag).sum());
    }

    /**
     * Mueve los offsets del grupo en un tópico. Kafka solo lo permite con el grupo VACÍO (sin consumidores
     * conectados): mover el offset de un consumidor activo sería pisarle su posición. OJO: pausar un listener
     * no basta (sigue siendo miembro del grupo); hay que DETENERLO.
     */
    public GroupDetail resetOffsets(String groupId, ResetOffsetsRequest req) {
        GroupDetail actual = describeGroup(groupId);
        if (!actual.members().isEmpty()) {
            throw new ConflictException("El grupo '" + groupId + "' tiene " + actual.members().size()
                    + " consumidor(es) activo(s). Detén sus consumidores antes de resetear (pausar no basta)");
        }
        TopicDetail topic = describeTopic(req.topic());
        List<TopicPartition> targets = topic.partitions().stream()
                .map(PartitionDetail::partition)
                .filter(p -> req.partition() == null || p == req.partition())
                .map(p -> new TopicPartition(req.topic(), p))
                .toList();
        if (targets.isEmpty()) {
            throw new BadRequestException("El tópico " + req.topic() + " no tiene la partición " + req.partition());
        }

        Map<TopicPartition, Long> earliest = endOffsets(targets, OffsetSpec.earliest());
        Map<TopicPartition, Long> latest = endOffsets(targets, OffsetSpec.latest());
        Map<TopicPartition, OffsetAndMetadata> nuevos = new HashMap<>();
        for (TopicPartition tp : targets) {
            long destino = switch (req.strategy()) {
                case EARLIEST -> earliest.get(tp);
                case LATEST -> latest.get(tp);
                // Se acota al rango que existe: un offset fuera del log no apuntaría a ningún mensaje.
                case OFFSET -> Math.max(earliest.get(tp), Math.min(req.offset(), latest.get(tp)));
            };
            nuevos.put(tp, new OffsetAndMetadata(destino));
        }
        await(admin.alterConsumerGroupOffsets(groupId, nuevos).all(), "resetear offsets de " + groupId);
        log.warn("Offsets del grupo {} en {} reseteados ({}{}): {}", groupId, req.topic(), req.strategy(),
                req.strategy() == ResetStrategy.OFFSET ? " " + req.offset() : "",
                nuevos.entrySet().stream().collect(Collectors.toMap(e -> e.getKey().partition(),
                        e -> e.getValue().offset())));
        return describeGroup(groupId);
    }

    // ------------------------------------------------------------------ apoyo

    private Map<TopicPartition, OffsetAndMetadata> committedOffsets(String groupId) {
        Map<TopicPartition, OffsetAndMetadata> raw = await(
                admin.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata(),
                "leer offsets de " + groupId);
        Map<TopicPartition, OffsetAndMetadata> result = new HashMap<>();
        raw.forEach((tp, om) -> {
            if (om != null) {
                result.put(tp, om);
            }
        });
        return result;
    }

    private Map<TopicPartition, Long> endOffsets(Collection<TopicPartition> partitions, OffsetSpec spec) {
        if (partitions.isEmpty()) {
            return Map.of();
        }
        Map<TopicPartition, OffsetSpec> request = partitions.stream()
                .collect(Collectors.toMap(tp -> tp, tp -> spec));
        return await(admin.listOffsets(request).all(), "leer offsets del log").entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().offset()));
    }

    boolean isProtected(String name) {
        return name.startsWith("__") || props.admin().protectedTopics().contains(name);
    }

    private static void guardInternal(String name, String operacion) {
        if (name.startsWith("__")) {
            throw new ProtectedResourceException("'" + name + "' es un tópico interno de Kafka: no se puede " + operacion);
        }
    }

    private static int replicationFactor(TopicDescription d) {
        return d.partitions().isEmpty() ? 0 : d.partitions().get(0).replicas().size();
    }

    private static List<Integer> ids(List<Node> nodes) {
        return nodes.stream().map(Node::id).sorted().toList();
    }

    /** Espera el resultado de una operación del AdminClient y traduce los errores de Kafka a errores de negocio. */
    <T> T await(KafkaFuture<T> future, String operacion) {
        try {
            return future.get(props.kafka().timeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerUnavailableException("Operación interrumpida: " + operacion, e);
        } catch (TimeoutException e) {
            throw new BrokerUnavailableException("Kafka no respondió a tiempo al " + operacion, e);
        } catch (ExecutionException e) {
            throw translate(e.getCause() == null ? e : e.getCause(), operacion);
        }
    }

    static RuntimeException translate(Throwable cause, String operacion) {
        String detalle = "No se pudo " + operacion + ": " + cause.getMessage();
        if (cause instanceof UnknownTopicOrPartitionException || cause instanceof GroupIdNotFoundException) {
            return new NotFoundException(detalle);
        }
        if (cause instanceof TopicExistsException || cause instanceof GroupNotEmptyException
                || cause instanceof UnknownMemberIdException) {
            return new ConflictException(detalle);
        }
        if (cause instanceof InvalidConfigurationException || cause instanceof InvalidPartitionsException
                || cause instanceof InvalidReplicationFactorException || cause instanceof InvalidTopicException
                || cause instanceof PolicyViolationException || cause instanceof InvalidRequestException) {
            return new BadRequestException(detalle);
        }
        if (cause instanceof org.apache.kafka.common.errors.TimeoutException) {
            return new BrokerUnavailableException("Kafka no respondió a tiempo al " + operacion, cause);
        }
        return new BrokerUnavailableException(detalle, cause);
    }
}
