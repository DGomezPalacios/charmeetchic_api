package com.charmeetchic.kafkaadmin.service;

import com.charmeetchic.kafkaadmin.config.AppProperties;
import com.charmeetchic.kafkaadmin.dto.AdminRequests.CreateTopicRequest;
import com.charmeetchic.kafkaadmin.dto.AdminRequests.ResetOffsetsRequest;
import com.charmeetchic.kafkaadmin.dto.AdminRequests.ResetStrategy;
import com.charmeetchic.kafkaadmin.dto.AdminResponses.GroupDetail;
import com.charmeetchic.kafkaadmin.dto.AdminResponses.TopicDetail;
import com.charmeetchic.kafkaadmin.exception.AdminExceptions.BadRequestException;
import com.charmeetchic.kafkaadmin.exception.AdminExceptions.BrokerUnavailableException;
import com.charmeetchic.kafkaadmin.exception.AdminExceptions.ConflictException;
import com.charmeetchic.kafkaadmin.exception.AdminExceptions.NotFoundException;
import com.charmeetchic.kafkaadmin.exception.AdminExceptions.ProtectedResourceException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.GroupNotEmptyException;
import org.apache.kafka.common.errors.InvalidConfigurationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El servicio contra un broker Kafka EMBEBIDO real (sin Docker): las operaciones del AdminClient y la
 * traducción de errores se prueban de verdad, no con mocks.
 */

class KafkaAdminServiceTest {

    private static Admin admin;
    private static KafkaAdminService service;
    private static String brokers;
    private static EmbeddedKafkaBroker broker;

    @BeforeAll
    static void setUp() {
        broker = new EmbeddedKafkaKraftBroker(1, 1);
        broker.afterPropertiesSet();
        brokers = broker.getBrokersAsString();
        admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, brokers));
        AppProperties props = new AppProperties(new AppProperties.Kafka(brokers, 10_000),
                new AppProperties.Admin(List.of("ventas.eventos"), 50, (short) 1),
                new AppProperties.Security("p", "http://jwks"));
        service = new KafkaAdminService(admin, props);
    }

    @AfterAll
    static void tearDown() {
        admin.close();
        try {
            broker.destroy();
        } catch (IllegalStateException e) {
            // En Windows Kafka no logra borrar los archivos de un tópico eliminado mientras siguen abiertos
            // (bloqueo del sistema de archivos). Solo afecta la limpieza del directorio temporal, no el test.
            System.err.println("Aviso: limpieza incompleta del broker embebido: " + e.getMessage());
        }
    }

    @Test
    void cicloDeVidaDeUnTopico() {
        TopicDetail created = service.createTopic(new CreateTopicRequest("demo.prueba", 2, (short) 1,
                Map.of("retention.ms", "3600000", "cleanup.policy", "delete")));
        assertThat(created.partitions()).hasSize(2);
        assertThat(created.partitions().get(0).enSincronia()).isTrue();
        assertThat(created.configs()).containsEntry("retention.ms", "3600000");
        assertThat(service.listTopics(false)).anyMatch(t -> t.name().equals("demo.prueba") && !t.protegido());

        TopicDetail updated = service.updateConfigs("demo.prueba", Map.of("retention.ms", "60000",
                "cleanup.policy", "compact"));
        assertThat(updated.configs()).containsEntry("retention.ms", "60000").containsEntry("cleanup.policy", "compact");

        assertThat(service.increasePartitions("demo.prueba", 4).partitions()).hasSize(4);
        assertThatThrownBy(() -> service.increasePartitions("demo.prueba", 3)).isInstanceOf(BadRequestException.class);

        assertThatThrownBy(() -> service.createTopic(new CreateTopicRequest("demo.prueba", 1, (short) 1, null)))
                .isInstanceOf(ConflictException.class);

        assertThat(service.deleteTopic("demo.prueba").operacion()).isEqualTo("DELETE");
    }

    @Test
    void erroresSeTraducenAHttp() {
        assertThatThrownBy(() -> service.describeTopic("no.existe")).isInstanceOf(NotFoundException.class);
        // RF mayor que los brokers disponibles (máximo configurado = 1)
        assertThatThrownBy(() -> service.createTopic(new CreateTopicRequest("rf.alto", 1, (short) 2, null)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.describeGroup("grupo-inexistente")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void topicosProtegidosEInternos() {
        service.createTopic(new CreateTopicRequest("ventas.eventos", 1, (short) 1, null));
        assertThatThrownBy(() -> service.deleteTopic("ventas.eventos")).isInstanceOf(ProtectedResourceException.class);
        assertThatThrownBy(() -> service.deleteTopic("__consumer_offsets")).isInstanceOf(ProtectedResourceException.class);
        assertThatThrownBy(() -> service.updateConfigs("__consumer_offsets", Map.of("retention.ms", "1")))
                .isInstanceOf(ProtectedResourceException.class);
        assertThatThrownBy(() -> service.createTopic(new CreateTopicRequest("__mio", 1, (short) 1, null)))
                .isInstanceOf(ProtectedResourceException.class);
        // un tópico del sistema SÍ se puede describir y ajustar
        assertThat(service.describeTopic("ventas.eventos").protegido()).isTrue();
    }

    @Test
    void lagPorParticionYResetDeOffsets() {
        service.createTopic(new CreateTopicRequest("lag.demo", 1, (short) 1, null));
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            for (int i = 0; i < 10; i++) {
                producer.send(new ProducerRecord<>("lag.demo", "k" + i, "v" + i));
            }
            producer.flush();
        }

        // Un consumidor lee 10, confirma solo hasta el offset 4 y sigue conectado (grupo con miembros)
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers,
                ConsumerConfig.GROUP_ID_CONFIG, "grupo-demo",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of("lag.demo"));
            int leidos = 0;
            long hasta = System.currentTimeMillis() + 20_000;
            while (leidos < 10 && System.currentTimeMillis() < hasta) {
                leidos += consumer.poll(Duration.ofMillis(500)).count();
            }
            consumer.commitSync(Map.of(new org.apache.kafka.common.TopicPartition("lag.demo", 0),
                    new org.apache.kafka.clients.consumer.OffsetAndMetadata(4)));

            GroupDetail activo = service.describeGroup("grupo-demo");
            assertThat(activo.members()).hasSize(1);
            assertThat(activo.lagTotal()).isEqualTo(6);
            assertThat(activo.offsets()).singleElement().satisfies(p -> {
                assertThat(p.committed()).isEqualTo(4);
                assertThat(p.logEnd()).isEqualTo(10);
            });
            // con consumidores conectados no se puede resetear
            assertThatThrownBy(() -> service.resetOffsets("grupo-demo",
                    new ResetOffsetsRequest("lag.demo", ResetStrategy.EARLIEST, null, null)))
                    .isInstanceOf(ConflictException.class);
        }

        // grupo detenido (vacío): ahora sí
        assertThat(service.resetOffsets("grupo-demo",
                new ResetOffsetsRequest("lag.demo", ResetStrategy.LATEST, null, null)).lagTotal()).isZero();
        assertThat(service.resetOffsets("grupo-demo",
                new ResetOffsetsRequest("lag.demo", ResetStrategy.EARLIEST, null, 0)).lagTotal()).isEqualTo(10);
        assertThat(service.resetOffsets("grupo-demo",
                new ResetOffsetsRequest("lag.demo", ResetStrategy.OFFSET, 999L, null)).lagTotal()).isZero();
        assertThatThrownBy(() -> service.resetOffsets("grupo-demo",
                new ResetOffsetsRequest("lag.demo", ResetStrategy.OFFSET, 1L, 7)))
                .isInstanceOf(BadRequestException.class);
        assertThat(service.listGroups()).anyMatch(g -> g.groupId().equals("grupo-demo"));
    }

    @Test
    void traduccionDeExcepciones() {
        assertThat(KafkaAdminService.translate(new GroupNotEmptyException("x"), "op")).isInstanceOf(ConflictException.class);
        assertThat(KafkaAdminService.translate(new InvalidConfigurationException("x"), "op"))
                .isInstanceOf(BadRequestException.class);
        assertThat(KafkaAdminService.translate(new TimeoutException("x"), "op"))
                .isInstanceOf(BrokerUnavailableException.class);
        assertThat(KafkaAdminService.translate(new IllegalStateException("x"), "op"))
                .isInstanceOf(BrokerUnavailableException.class);
    }
}
