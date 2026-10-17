package com.charmeetchic.rabbitadmin.service;

import com.charmeetchic.rabbitadmin.client.RabbitManagementClient;
import com.charmeetchic.rabbitadmin.client.RabbitManagementClient.BindingDetails;
import com.charmeetchic.rabbitadmin.client.RabbitManagementClient.ExchangeDetails;
import com.charmeetchic.rabbitadmin.client.RabbitManagementClient.QueueDetails;
import com.charmeetchic.rabbitadmin.config.AppProperties;
import com.charmeetchic.rabbitadmin.dto.BindingRequest;
import com.charmeetchic.rabbitadmin.dto.ExchangeRequest;
import com.charmeetchic.rabbitadmin.dto.QueueRequest;
import com.charmeetchic.rabbitadmin.dto.ResourceInfo.Declared;
import com.charmeetchic.rabbitadmin.dto.ResourceInfo.QueueInfo;
import com.charmeetchic.rabbitadmin.exception.AdminExceptions.ConflictException;
import com.charmeetchic.rabbitadmin.exception.AdminExceptions.NotFoundException;
import com.charmeetchic.rabbitadmin.exception.AdminExceptions.ProtectedResourceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RabbitAdminServiceTest {

    private final AmqpAdmin amqpAdmin = mock(AmqpAdmin.class);
    private final RabbitManagementClient management = mock(RabbitManagementClient.class);
    private final RabbitAdminService service = new RabbitAdminService(amqpAdmin, management, new AppProperties(
            new AppProperties.Rabbitmq("http://localhost:15672"),
            new AppProperties.Admin(List.of("charmeetchic.", "inventario.", "notificaciones.")),
            new AppProperties.Security("charme-et-chic", "http://jwks")));

    // ------------------------------------------------------------------ colas

    @Test
    void crearColaNueva_laDeclaraConSusArgumentos() {
        when(management.getQueue("pedidos.web")).thenReturn(Optional.empty());
        when(management.getExchange("mi.dlx")).thenReturn(Optional.of(exchange("mi.dlx", "direct")));

        Declared<QueueInfo> result = service.createQueue(new QueueRequest("pedidos.web", null, null, "mi.dlx",
                "pedidos.dlq", 60_000L, null));

        assertThat(result.created()).isTrue();
        ArgumentCaptor<Queue> queue = ArgumentCaptor.forClass(Queue.class);
        verify(amqpAdmin).declareQueue(queue.capture());
        assertThat(queue.getValue().getName()).isEqualTo("pedidos.web");
        assertThat(queue.getValue().isDurable()).isTrue();
        assertThat(queue.getValue().getArguments())
                .containsEntry("x-dead-letter-exchange", "mi.dlx")
                .containsEntry("x-dead-letter-routing-key", "pedidos.dlq")
                .containsEntry("x-message-ttl", 60_000L);
    }

    @Test
    void crearColaQueYaExisteIgual_noFallaYNoRedeclara() {
        // RabbitMQ agrega x-queue-type por su cuenta: no debe contar como diferencia
        when(management.getQueue("pedidos.web")).thenReturn(Optional.of(
                new QueueDetails("pedidos.web", true, false, Map.of("x-queue-type", "classic"), 0, 0)));

        Declared<QueueInfo> result = service.createQueue(simpleQueue("pedidos.web"));

        assertThat(result.created()).isFalse();
        verify(amqpAdmin, never()).declareQueue(any());
    }

    @Test
    void crearColaQueExisteConOtraConfiguracion_es409ConLaDiferencia() {
        when(management.getQueue("pedidos.web")).thenReturn(Optional.of(
                new QueueDetails("pedidos.web", false, false, Map.of("x-message-ttl", 1000), 0, 0)));

        assertThatThrownBy(() -> service.createQueue(simpleQueue("pedidos.web")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("durable es false (pediste true)")
                .hasMessageContaining("x-message-ttl es 1000 (pediste (sin definir))");
        verify(amqpAdmin, never()).declareQueue(any());
    }

    @Test
    void crearColaConDeadLetterExchangeInexistente_es404() {
        when(management.getExchange("no.existe")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createQueue(new QueueRequest("pedidos.web", null, null, "no.existe",
                null, null, null)))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("'no.existe' no existe");
    }

    @Test
    void crearColaConPrefijoDelSistema_es403() {
        assertThatThrownBy(() -> service.createQueue(simpleQueue("inventario.nueva")))
                .isInstanceOf(ProtectedResourceException.class)
                .hasMessageContaining("pertenece a la API de inventario");
        verify(amqpAdmin, never()).declareQueue(any());
    }

    @Test
    void borrarColaDelSistema_es403YNoLlegaARabbit() {
        for (String name : List.of("inventario.stock-bajo", "notificaciones.reportes.dlq", "charmeetchic.algo")) {
            assertThatThrownBy(() -> service.deleteQueue(name, false, false))
                    .isInstanceOf(ProtectedResourceException.class);
        }
        verify(amqpAdmin, never()).deleteQueue(anyString());
    }

    @Test
    void borrarColaInexistente_es404() {
        when(amqpAdmin.getQueueInfo("fantasma")).thenReturn(null);

        assertThatThrownBy(() -> service.deleteQueue("fantasma", false, false))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void borrarColaExistente_laBorra() {
        when(amqpAdmin.getQueueInfo("pedidos.web")).thenReturn(new QueueInformation("pedidos.web", 0, 0));

        service.deleteQueue("pedidos.web", false, false);

        verify(amqpAdmin).deleteQueue("pedidos.web");
    }

    @Test
    void borrarColaConMensajesUsandoIfEmpty_es409() {
        when(amqpAdmin.getQueueInfo("pedidos.web")).thenReturn(new QueueInformation("pedidos.web", 5, 0));
        doThrow(new AmqpIOException(new IOException("channel error; reply-code=406, reply-text=PRECONDITION_FAILED - queue not empty")))
                .when(amqpAdmin).deleteQueue("pedidos.web", false, true);

        assertThatThrownBy(() -> service.deleteQueue("pedidos.web", false, true))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("tiene mensajes");
    }

    // ------------------------------------------------------------------ exchanges

    @Test
    void crearExchangeNuevo_loDeclaraConSuTipo() {
        when(management.getExchange("pedidos")).thenReturn(Optional.empty());

        assertThat(service.createExchange(new ExchangeRequest("pedidos", "TOPIC", null, null)).created()).isTrue();

        ArgumentCaptor<Exchange> exchange = ArgumentCaptor.forClass(Exchange.class);
        verify(amqpAdmin).declareExchange(exchange.capture());
        assertThat(exchange.getValue().getType()).isEqualTo("topic");
        assertThat(exchange.getValue().isDurable()).isTrue();
        assertThat(exchange.getValue().isAutoDelete()).isFalse();
    }

    @Test
    void crearExchangeQueExisteConOtroTipo_es409() {
        when(management.getExchange("pedidos")).thenReturn(Optional.of(exchange("pedidos", "direct")));

        assertThatThrownBy(() -> service.createExchange(new ExchangeRequest("pedidos", "fanout", null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("es de tipo direct (pediste fanout)");
    }

    @Test
    void borrarExchangeDelSistema_es403() {
        assertThatThrownBy(() -> service.deleteExchange("charmeetchic.dlx"))
                .isInstanceOf(ProtectedResourceException.class);
        verify(amqpAdmin, never()).deleteExchange(anyString());
    }

    @Test
    void borrarExchangeInexistente_es404() {
        when(management.getExchange("fantasma")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteExchange("fantasma")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void listarExchanges_ocultaElPorDefectoYLosAmqYMarcaLosDelSistema() {
        when(management.listExchanges()).thenReturn(List.of(exchange("", "direct"), exchange("amq.topic", "topic"),
                exchange("charmeetchic.inventario", "direct"), exchange("pedidos", "fanout")));

        assertThat(service.listExchanges())
                .extracting(e -> e.name() + ":" + e.system())
                .containsExactly("charmeetchic.inventario:true", "pedidos:false");
    }

    // ------------------------------------------------------------------ bindings

    @Test
    void crearBinding_conColaInexistente_es404() {
        when(amqpAdmin.getQueueInfo("fantasma")).thenReturn(null);

        assertThatThrownBy(() -> service.createBinding(new BindingRequest("pedidos", "fantasma", "rk")))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("La cola 'fantasma' no existe");
    }

    @Test
    void crearBinding_conExchangeInexistente_es404() {
        when(amqpAdmin.getQueueInfo("copia")).thenReturn(new QueueInformation("copia", 0, 0));
        when(management.getExchange("fantasma")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createBinding(new BindingRequest("fantasma", "copia", "rk")))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("El exchange 'fantasma' no existe");
    }

    @Test
    void crearBinding_desdeExchangeDelSistemaHaciaColaPropia_estaPermitido() {
        when(amqpAdmin.getQueueInfo("auditoria.copia")).thenReturn(new QueueInformation("auditoria.copia", 0, 0));
        when(management.getExchange("charmeetchic.inventario"))
                .thenReturn(Optional.of(exchange("charmeetchic.inventario", "direct")));
        when(management.getBindings("charmeetchic.inventario", "auditoria.copia")).thenReturn(List.of());

        boolean created = service.createBinding(new BindingRequest("charmeetchic.inventario", "auditoria.copia",
                "inventario.movimientos")).created();

        assertThat(created).isTrue();
        ArgumentCaptor<Binding> binding = ArgumentCaptor.forClass(Binding.class);
        verify(amqpAdmin).declareBinding(binding.capture());
        assertThat(binding.getValue().getExchange()).isEqualTo("charmeetchic.inventario");
        assertThat(binding.getValue().getDestination()).isEqualTo("auditoria.copia");
        assertThat(binding.getValue().getRoutingKey()).isEqualTo("inventario.movimientos");
    }

    @Test
    void bindingHaciaColaDelSistema_es403AlCrearYAlBorrar() {
        BindingRequest toSystemQueue = new BindingRequest("pedidos", "inventario.movimientos", "x");

        assertThatThrownBy(() -> service.createBinding(toSystemQueue)).isInstanceOf(ProtectedResourceException.class);
        assertThatThrownBy(() -> service.deleteBinding(toSystemQueue)).isInstanceOf(ProtectedResourceException.class);
        verify(amqpAdmin, never()).declareBinding(any());
        verify(amqpAdmin, never()).removeBinding(any());
    }

    @Test
    void borrarBindingInexistente_es404() {
        when(management.getBindings("pedidos", "copia"))
                .thenReturn(List.of(new BindingDetails("pedidos", "copia", "queue", "otra.key")));

        assertThatThrownBy(() -> service.deleteBinding(new BindingRequest("pedidos", "copia", "rk")))
                .isInstanceOf(NotFoundException.class);
        verify(amqpAdmin, never()).removeBinding(any());
    }

    @Test
    void borrarBindingExistente_loBorra() {
        when(management.getBindings("pedidos", "copia"))
                .thenReturn(List.of(new BindingDetails("pedidos", "copia", "queue", "rk")));

        service.deleteBinding(new BindingRequest("pedidos", "copia", "rk"));

        verify(amqpAdmin).removeBinding(any(Binding.class));
    }

    private static QueueRequest simpleQueue(String name) {
        return new QueueRequest(name, null, null, null, null, null, null);
    }

    private static ExchangeDetails exchange(String name, String type) {
        return new ExchangeDetails(name, type, true, false, false);
    }
}
