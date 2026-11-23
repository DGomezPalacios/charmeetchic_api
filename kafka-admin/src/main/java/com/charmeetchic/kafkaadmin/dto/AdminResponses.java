package com.charmeetchic.kafkaadmin.dto;

import java.util.List;
import java.util.Map;

/** Respuestas de la API. Solo datos: no exponen clases del cliente de Kafka. */
public final class AdminResponses {

    private AdminResponses() {
    }

    /** Fila de GET /topics. {@code protegido} = no se puede borrar desde aquí. */
    public record TopicSummary(String name, int partitions, int replicationFactor, boolean internal, boolean protegido) {
    }

    /**
     * Una partición: quién es líder, dónde están las réplicas y cuáles están al día (ISR).
     *
     * @param enSincronia true si todas las réplicas están en el ISR (ninguna atrasada)
     */
    public record PartitionDetail(int partition, Integer leader, List<Integer> replicas, List<Integer> isr,
                                  boolean enSincronia) {
    }

    /** GET /topics/{name}: particiones con líderes/ISR y configs relevantes. */
    public record TopicDetail(String name, boolean internal, boolean protegido, int replicationFactor,
                              List<PartitionDetail> partitions, Map<String, String> configs) {
    }

    /** Fila de GET /consumer-groups. */
    public record GroupSummary(String groupId, String state) {
    }

    /** Un consumidor del grupo y las particiones que tiene asignadas. */
    public record MemberDetail(String clientId, String host, List<String> assignments) {
    }

    /**
     * Offset confirmado del grupo vs. final del log en una partición.
     *
     * @param committed offset confirmado por el grupo (null si nunca confirmó en esa partición)
     * @param logEnd    offset del próximo mensaje que se escribirá
     * @param lag       mensajes pendientes = logEnd - committed
     */
    public record PartitionLag(String topic, int partition, Long committed, long logEnd, long lag) {
    }

    /** GET /consumer-groups/{groupId}: estado, miembros, offsets y lag. */
    public record GroupDetail(String groupId, String state, Integer coordinator, List<MemberDetail> members,
                              List<PartitionLag> offsets, long lagTotal) {
    }

    /** Resultado de una operación de escritura. */
    public record OperationResult(String recurso, String operacion, String detalle) {
    }
}
