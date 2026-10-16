package com.charmeetchic.api.messaging;

import com.charmeetchic.api.dto.CartItemDTO;
import com.charmeetchic.api.dto.ReportJobDTO;
import com.charmeetchic.api.entity.Product;
import com.charmeetchic.api.enums.MovementType;
import com.charmeetchic.api.enums.ReportStatus;
import com.charmeetchic.api.enums.SimulatedFailure;
import com.charmeetchic.api.exception.InsufficientStockException;
import com.charmeetchic.api.messaging.consumer.inventario.StockMovementConsumer;
import com.charmeetchic.api.messaging.consumer.notificaciones.ReportRequestConsumer;
import com.charmeetchic.api.messaging.event.LowStockEvent;
import com.charmeetchic.api.messaging.event.ReportRequestedEvent;
import com.charmeetchic.api.messaging.event.StockMovementEvent;
import com.charmeetchic.api.repository.ProductRepository;
import com.charmeetchic.api.service.AsyncReportService;
import com.charmeetchic.api.service.OrderService;
import com.charmeetchic.api.service.StockAuditService;
import com.rabbitmq.client.Channel;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mensajería con el contexto real de Spring y la BD H2, sin RabbitMQ: el {@link RabbitTemplate} se reemplaza
 * por un mock para ver QUÉ se publicaría, y los consumidores se invocan con un Channel simulado.
 *
 * <p>Usa su propia BD en memoria para no interferir con los otros tests de integración.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.messaging.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:messagingtest",
        "app.jwt.mode=local",
        "app.jwt.local-secret=" + MessagingIntegrationTest.SECRET
})
class MessagingIntegrationTest {

    static final String SECRET = "messaging-test-secret-messaging-test-12345";

    @MockBean
    private RabbitTemplate rabbitTemplate;

    @Autowired private MockMvc mvc;
    @Autowired private OrderService orderService;
    @Autowired private ProductRepository productRepository;
    @Autowired private AsyncReportService asyncReportService;
    @Autowired private StockAuditService stockAuditService;
    @Autowired private ReportRequestConsumer reportConsumer;
    @Autowired private StockMovementConsumer movementConsumer;
    @Autowired private MessageConverter messageConverter;

    @BeforeEach
    void reset() {
        clearInvocations(rabbitTemplate);
    }

    // ------------------------------------------------------------------ productores + transacción

    @Test
    void ordenConfirmada_publicaUnMovimientoPorProducto() {
        Product product = bySku("ANI-SET-003"); // stock 40, lejos del umbral

        orderService.createOrder("u-msg-1", "Calle 1", List.of(new CartItemDTO(product.getId(), 2)));

        StockMovementEvent event = captured("charmeetchic.inventario", "inventario.movimientos",
                StockMovementEvent.class).get(0);
        assertThat(event.productId()).isEqualTo(product.getId());
        assertThat(event.type()).isEqualTo(MovementType.VENTA);
        assertThat(event.quantity()).isEqualTo(-2);
        assertThat(event.reference()).startsWith("orden #");
        assertThat(captured("charmeetchic.inventario", "inventario.stock-bajo", LowStockEvent.class)).isEmpty();
    }

    @Test
    void ordenQueHaceRollback_noPublicaMovimientosFalsos() {
        Product ok = bySku("ARE-ARG-001");   // la primera línea SÍ alcanzaría a descontarse...
        Product scarce = bySku("PUL-CHA-003"); // ...pero esta no tiene stock suficiente

        assertThatThrownBy(() -> orderService.createOrder("u-msg-2", "Calle 2",
                List.of(new CartItemDTO(ok.getId(), 1), new CartItemDTO(scarce.getId(), 999))))
                .isInstanceOf(InsufficientStockException.class);

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class),
                any(MessagePostProcessor.class));
        assertThat(bySku("ARE-ARG-001").getStock()).isEqualTo(ok.getStock()); // y el stock no cambió
    }

    @Test
    void cruzarElUmbral_publicaAlertaDeStockBajo() {
        Product product = bySku("ACC-EST-003"); // stock 8, umbral 5

        orderService.createOrder("u-msg-3", "Calle 3", List.of(new CartItemDTO(product.getId(), 4))); // 8 -> 4

        LowStockEvent alert = captured("charmeetchic.inventario", "inventario.stock-bajo", LowStockEvent.class).get(0);
        assertThat(alert.sku()).isEqualTo("ACC-EST-003");
        assertThat(alert.stock()).isEqualTo(4);
    }

    // ------------------------------------------------------------------ consumidores

    @Test
    void consumidorDeMovimientos_guardaLaAuditoriaYEsIdempotente() throws Exception {
        String eventId = UUID.randomUUID().toString();
        StockMovementEvent event = new StockMovementEvent(eventId, 999L, "TEST-SKU", "Prueba",
                MovementType.AJUSTE_MANUAL, 5, 1, 6, "test", Instant.now());
        Channel channel = mock(Channel.class);

        movementConsumer.onStockMovement(toMessage(event, eventId, "inventario.movimientos", 1L), channel);
        movementConsumer.onStockMovement(toMessage(event, eventId, "inventario.movimientos", 2L), channel); // duplicado

        verify(channel).basicAck(1L, false);
        verify(channel).basicAck(2L, false);
        assertThat(stockAuditService.getMovements(999L, Pageable.unpaged()).getContent())
                .singleElement().satisfies(m -> assertThat(m.eventId()).isEqualTo(eventId));
    }

    @Test
    void flujoCompletoDeReporte_postPublicaYElConsumidorLoCompleta() throws Exception {
        ReportJobDTO job = asyncReportService.requestInventoryReport("admin-1", null);
        assertThat(job.status()).isEqualTo(ReportStatus.PENDIENTE);

        ReportRequestedEvent published = captured("charmeetchic.notificaciones", "notificacion.reporte.inventario",
                ReportRequestedEvent.class).get(0);
        assertThat(published.reportId()).isEqualTo(job.id());

        Channel channel = mock(Channel.class);
        reportConsumer.onReportRequested(toMessage(published, job.id(), "notificaciones.reportes", 5L), channel);

        verify(channel).basicAck(5L, false);
        ReportJobDTO done = asyncReportService.getById(job.id());
        assertThat(done.status()).isEqualTo(ReportStatus.COMPLETADO);
        assertThat(done.result()).contains("inventoryValue").contains("lowStockProducts");
    }

    @Test
    void reporteQueTerminaEnLaDlq_quedaFallido() throws Exception {
        ReportJobDTO job = asyncReportService.requestInventoryReport("admin-1", null);
        ReportRequestedEvent published = captured("charmeetchic.notificaciones", "notificacion.reporte.inventario",
                ReportRequestedEvent.class).get(0);
        ReportRequestedEvent invalid = new ReportRequestedEvent(published.reportId(), published.reportType(),
                published.requestedBy(), published.requestedAt(), SimulatedFailure.INVALIDO);

        Channel channel = mock(Channel.class);
        reportConsumer.onReportRequested(toMessage(invalid, job.id(), "notificaciones.reportes", 6L), channel);

        verify(channel).basicNack(6L, false, false);
        ReportJobDTO failed = asyncReportService.getById(job.id());
        assertThat(failed.status()).isEqualTo(ReportStatus.FALLIDO);
        assertThat(failed.error()).contains("mensaje inválido");
    }

    @Test
    void losListenersNoConviertenElJson_loHaceManualAckProcessor(
            @Autowired SimpleRabbitListenerContainerFactory factory) {
        // Si Spring convirtiera antes de llamar al consumidor, un JSON malo quedaría sin ACK ni NACK.
        Object converter = ReflectionTestUtils.getField(factory, "messageConverter");
        assertThat(converter).isNotNull()
                .extracting(c -> c.getClass().getSimpleName()).isEqualTo("RawBodyMessageConverter");
        assertThat(ReflectionTestUtils.getField(factory, "acknowledgeMode")).isEqualTo(AcknowledgeMode.MANUAL);
    }

    // ------------------------------------------------------------------ endpoints

    @Test
    void postReporteAsincrono_responde202ConLocationYSePuedeConsultar() throws Exception {
        String location = mvc.perform(post("/reports/inventory/async").header("Authorization", bearer("ADMIN")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDIENTE"))
                .andExpect(header().exists("Location"))
                .andReturn().getResponse().getHeader("Location");

        String id = location.substring(location.lastIndexOf('/') + 1);
        mvc.perform(get("/reports/async/" + id).header("Authorization", bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.reportType").value("inventario"));
    }

    @Test
    void postReporteAsincrono_validaPermisosYElCuerpo() throws Exception {
        mvc.perform(post("/reports/inventory/async").header("Authorization", bearer("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/reports/inventory/async").header("Authorization", bearer("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"simularError\":\"NO_EXISTE\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/reports/async/no-existe").header("Authorization", bearer("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void auditoriaDeMovimientos_esSoloAdmin() throws Exception {
        mvc.perform(get("/reports/stock-movements").header("Authorization", bearer("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/reports/stock-movements").header("Authorization", bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    // ------------------------------------------------------------------ helpers

    private Product bySku(String sku) {
        return productRepository.findAll().stream().filter(p -> p.getSku().equals(sku)).findFirst().orElseThrow();
    }

    /** Payloads enviados con convertAndSend a ese exchange/routing key. */
    private <T> List<T> captured(String exchange, String routingKey, Class<T> type) {
        ArgumentCaptor<String> exchanges = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, atLeastOnce()).convertAndSend(exchanges.capture(), keys.capture(), payloads.capture(),
                any(MessagePostProcessor.class));
        return IntStream.range(0, payloads.getAllValues().size())
                .filter(i -> exchanges.getAllValues().get(i).equals(exchange)
                        && keys.getAllValues().get(i).equals(routingKey))
                .mapToObj(i -> payloads.getAllValues().get(i))
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    /** Lo que RabbitMQ entregaría al consumidor: JSON + propiedades de entrega. */
    private Message toMessage(Object payload, String messageId, String queue, long deliveryTag) {
        MessageProperties props = new MessageProperties();
        props.setMessageId(messageId);
        props.setConsumerQueue(queue);
        props.setDeliveryTag(deliveryTag);
        return messageConverter.toMessage(payload, props);
    }

    private static String bearer(String role) {
        return "Bearer " + Jwts.builder()
                .subject("uid-" + role.toLowerCase())
                .claim("email", role.toLowerCase() + "@charme.cl").claim("name", "Usuario " + role)
                .claim("roles", List.of(role))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
