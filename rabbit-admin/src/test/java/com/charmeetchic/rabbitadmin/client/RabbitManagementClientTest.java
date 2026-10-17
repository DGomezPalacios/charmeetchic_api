package com.charmeetchic.rabbitadmin.client;

import com.charmeetchic.rabbitadmin.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Cliente HTTP de la API de administración, contra un servidor HTTP simulado (sin RabbitMQ). */
class RabbitManagementClientTest {

    private MockRestServiceServer server;
    private RabbitManagementClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RabbitProperties rabbit = new RabbitProperties(); // guest/guest, vhost "/"
        client = new RabbitManagementClient(builder, new AppProperties(
                new AppProperties.Rabbitmq("http://rabbit:15672"),
                new AppProperties.Admin(List.of("charmeetchic.")),
                new AppProperties.Security("charme-et-chic", "http://jwks"),
                new AppProperties.Alerts(false, 30_000, 80, ".dlq")), rabbit);
    }

    @Test
    void codificaElVhostRaizComo2FYEnviaBasicAuth() {
        server.expect(requestTo("http://rabbit:15672/api/queues/%2F"))
                .andExpect(header("Authorization", "Basic Z3Vlc3Q6Z3Vlc3Q=")) // guest:guest
                .andRespond(withSuccess("""
                        [{"name":"inventario.movimientos","durable":true,"auto_delete":false,
                          "arguments":{"x-dead-letter-exchange":"charmeetchic.dlx"},"messages":3,"consumers":1,
                          "otro_campo":"se ignora"}]""", MediaType.APPLICATION_JSON));

        List<RabbitManagementClient.QueueDetails> queues = client.listQueues();

        assertThat(queues).singleElement().satisfies(q -> {
            assertThat(q.name()).isEqualTo("inventario.movimientos");
            assertThat(q.autoDelete()).isFalse();
            assertThat(q.messages()).isEqualTo(3);
            assertThat(q.arguments()).containsEntry("x-dead-letter-exchange", "charmeetchic.dlx");
        });
        server.verify();
    }

    @Test
    void recursoInexistente_devuelveVacioEnVezDeError() {
        server.expect(requestTo("http://rabbit:15672/api/exchanges/%2F/fantasma"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.getExchange("fantasma")).isEmpty();
    }

    @Test
    void leeLosBindingsEntreUnExchangeYUnaCola() {
        server.expect(requestTo("http://rabbit:15672/api/bindings/%2F/e/charmeetchic.notificaciones/q/notificaciones.reportes"))
                .andRespond(withSuccess("""
                        [{"source":"charmeetchic.notificaciones","destination":"notificaciones.reportes",
                          "destination_type":"queue","routing_key":"notificacion.reporte.*"}]""",
                        MediaType.APPLICATION_JSON));

        assertThat(client.getBindings("charmeetchic.notificaciones", "notificaciones.reportes"))
                .singleElement()
                .satisfies(b -> assertThat(b.routingKey()).isEqualTo("notificacion.reporte.*"));
    }
}
