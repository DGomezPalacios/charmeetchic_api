package com.charmeetchic.api.streaming;

import com.charmeetchic.api.dto.CartItemDTO;
import com.charmeetchic.api.dto.OrderDTO;
import com.charmeetchic.api.entity.Product;
import com.charmeetchic.api.repository.ProductRepository;
import com.charmeetchic.api.service.HistorialVentasService;
import com.charmeetchic.api.service.OrderService;
import com.charmeetchic.api.service.StockMonitorService;
import com.charmeetchic.api.service.VentasMetricasService;
import com.charmeetchic.api.streaming.config.KafkaConsumerConfig;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Streaming de punta a punta contra un broker Kafka EMBEBIDO (en memoria, sin Docker): beans NewTopic reales,
 * productor tras el commit, los 4 consumer groups, el manejo de errores (.DLT) y la pausa/reanudación.
 *
 * <p>Con 1 solo broker, RF y min.insync.replicas se bajan a 1 (en el clúster real son 3 y 2).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext
@EmbeddedKafka(partitions = 3, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@TestPropertySource(properties = {
        "app.streaming.enabled=true",
        "app.streaming.topics.ventas.replicas=1",
        "app.streaming.topics.ventas.configs[min.insync.replicas]=1",
        "app.streaming.topics.stock.replicas=1",
        "app.streaming.topics.stock.configs[min.insync.replicas]=1",
        "app.streaming.topics.dead-letter.replicas=1",
        "app.streaming.topics.dead-letter.configs[min.insync.replicas]=1",
        "spring.datasource.url=jdbc:h2:mem:streamingtest",
        "app.jwt.mode=local",
        "app.jwt.local-secret=" + StreamingKafkaIntegrationTest.SECRET
})
class StreamingKafkaIntegrationTest {

    static final String SECRET = "streaming-test-secret-streaming-test-1234";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** RabbitMQ no participa: se simula para que la orden no intente conectarse a un broker AMQP. */
    @MockBean
    private RabbitTemplate rabbitTemplate;

    @Autowired private MockMvc mvc;
    @Autowired private OrderService orderService;
    @Autowired private ProductRepository productRepository;
    @Autowired private VentasMetricasService ventasMetricas;
    @Autowired private HistorialVentasService historial;
    @Autowired private StockMonitorService stockMonitor;
    @Autowired private EmbeddedKafkaBroker broker;

    @Test
    void ventaReal_llegaALasMetricasAlHistorialYAlMonitorDeStock() throws Exception {
        Product product = productRepository.findBySkuAndActiveTrue("ANI-SET-003").orElseThrow();
        long ventasAntes = ventasMetricas.ventasTotales();
        long historialAntes = historial.resumen("historial-ventas").procesados();

        OrderDTO order = orderService.createOrder("u-kafka", "Calle Kafka 1",
                List.of(new CartItemDTO(product.getId(), 2)));
        int stockEsperado = product.getStock() - 2;

        // Dos grupos leen el MISMO mensaje de forma independiente, y el monitor recibe el nuevo stock
        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(ventasMetricas.ventasTotales()).isEqualTo(ventasAntes + 1);
            assertThat(historial.resumen("historial-ventas").procesados()).isGreaterThan(historialAntes);
            assertThat(stockMonitor.stockDe("ANI-SET-003")).isEqualTo(stockEsperado);
        });

        mvc.perform(get("/streaming/metrics").header("Authorization", bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generadoEn").exists())
                .andExpect(jsonPath("$.ventasUltimoMinuto").isNumber())
                .andExpect(jsonPath("$.ingresosTotales").isNumber())
                .andExpect(jsonPath("$.topProductos").isArray())
                .andExpect(jsonPath("$.stockCritico").isArray())
                .andExpect(jsonPath("$.ultimosEventos[*].detalle", hasItem("Orden " + order.id()
                        + " creada: 2 u., " + formatoClp(order.total()))));
    }

    @Test
    void eventosDefectuosos_terminanEnElDltConSuMotivo() throws Exception {
        mvc.perform(post("/streaming/simulate?ventas=0&defectuoso=true&tipoDefecto=VALIDACION")
                        .header("Authorization", bearer("ADMIN")))
                .andExpect(status().isAccepted());
        mvc.perform(post("/streaming/simulate?ventas=0&defectuoso=true&tipoDefecto=JSON")
                        .header("Authorization", bearer("ADMIN")))
                .andExpect(status().isAccepted());
        mvc.perform(post("/streaming/simulate?ventas=0&defectuoso=true&tipoDefecto=TRANSITORIO")
                        .header("Authorization", bearer("ADMIN")))
                .andExpect(status().isAccepted());

        Map<String, Object> props = KafkaTestUtils.consumerProps("test-dlt-reader", "false", broker);
        props.put("auto.offset.reset", "earliest");
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(),
                new StringDeserializer()).createConsumer()) {
            consumer.subscribe(List.of("ventas.eventos.DLT"));
            List<String> motivos = new ArrayList<>();
            await().atMost(TIMEOUT).untilAsserted(() -> {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> r : records) {
                    motivos.add(new String(r.headers().lastHeader(KafkaConsumerConfig.DLT_REASON_HEADER).value(),
                            StandardCharsets.UTF_8));
                }
                // Validación y JSON fallan en los 2 grupos de ventas; el transitorio solo en metricas-ventas
                assertThat(motivos).anyMatch(m -> m.contains("InvalidStreamingEventException"));
                assertThat(motivos).anyMatch(m -> m.contains("JsonParseException"));
                assertThat(motivos).anyMatch(m -> m.contains("SimulatedStreamingFailureException"));
                assertThat(motivos).hasSize(5);
            });
        }
    }

    @Test
    void pausarYReanudar_unListenerDejaDeConsumirYLuegoSePoneAlDia() throws Exception {
        mvc.perform(post("/streaming/consumers/historial-ventas/pause").header("Authorization", bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pausado").value(true))
                .andExpect(jsonPath("$.grupo").value("historial-ventas"));
        Thread.sleep(1500); // la pausa se hace efectiva en el siguiente poll
        long historialPausado = historial.resumen("historial-ventas").procesados();
        long metricasAntes = ventasMetricas.ventasTotales();

        mvc.perform(post("/streaming/simulate?ventas=20").header("Authorization", bearer("ADMIN")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.ventasPublicadas").value(20));

        // metricas-ventas sigue consumiendo; historial-ventas (pausado) no avanza
        await().atMost(TIMEOUT).until(() -> ventasMetricas.ventasTotales() >= metricasAntes + 20);
        assertThat(historial.resumen("historial-ventas").procesados()).isEqualTo(historialPausado);

        mvc.perform(post("/streaming/consumers/historial-ventas/resume").header("Authorization", bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pausado").value(false));
        await().atMost(TIMEOUT).until(() -> historial.resumen("historial-ventas").procesados() >= historialPausado + 20);

        mvc.perform(get("/streaming/consumers").header("Authorization", bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='metricas-ventas')].concurrencia", hasItem(3)));
    }

    @Test
    void streamingEsSoloParaAdmin() throws Exception {
        mvc.perform(get("/streaming/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/streaming/metrics").header("Authorization", bearer("USER"))).andExpect(status().isForbidden());
        mvc.perform(post("/streaming/consumers/no-existe/pause").header("Authorization", bearer("ADMIN")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/streaming/simulate?ventas=999999").header("Authorization", bearer("ADMIN")))
                .andExpect(status().isBadRequest());
        // detener = salir del grupo (corriendo=false); iniciar = volver a unirse
        mvc.perform(post("/streaming/consumers/monitor-dlt/stop").header("Authorization", bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.corriendo").value(false));
        mvc.perform(post("/streaming/consumers/monitor-dlt/start").header("Authorization", bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.corriendo").value(true));
        mvc.perform(get("/streaming/historial").header("Authorization", bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grupo").value("historial-ventas"));
    }

    private static String formatoClp(java.math.BigDecimal total) {
        return java.text.NumberFormat.getCurrencyInstance(java.util.Locale.forLanguageTag("es-CL")).format(total);
    }

    private static String bearer(String role) {
        return "Bearer " + Jwts.builder()
                .subject("uid-" + role.toLowerCase())
                .claim("email", role.toLowerCase() + "@charme.cl")
                .claim("roles", List.of(role))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
