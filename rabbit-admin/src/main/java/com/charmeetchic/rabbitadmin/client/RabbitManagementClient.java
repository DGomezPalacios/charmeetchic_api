package com.charmeetchic.rabbitadmin.client;

import com.charmeetchic.rabbitadmin.config.AppProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.DefaultUriBuilderFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cliente de SOLO LECTURA de la API HTTP del plugin de administración de RabbitMQ ({@code :15672/api}).
 *
 * <p><b>Por qué existe, si ya está {@code AmqpAdmin}:</b> el protocolo AMQP sirve para declarar y borrar, pero
 * no tiene operaciones para LISTAR colas/exchanges/bindings ni para leer la configuración de un exchange.
 * La consola web de RabbitMQ usa esta misma API. Las escrituras siguen siendo por AMQP con {@code AmqpAdmin}.
 *
 * <p>Uso interno de {@code RabbitAdminService}; los controladores no lo conocen.
 */
@Component
public class RabbitManagementClient {

    private final RestClient restClient;
    private final String vhost;

    public RabbitManagementClient(RestClient.Builder builder, AppProperties props, RabbitProperties rabbit) {
        // VALUES_ONLY: codifica TODOS los caracteres especiales de las variables. Así el vhost "/" viaja como
        // %2F (lo que exige RabbitMQ) en vez de partir la ruta en dos.
        DefaultUriBuilderFactory uriFactory = new DefaultUriBuilderFactory(props.rabbitmq().managementUrl());
        uriFactory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.VALUES_ONLY);
        this.restClient = builder
                .uriBuilderFactory(uriFactory)
                .defaultHeaders(h -> h.setBasicAuth(rabbit.getUsername(), rabbit.getPassword(), StandardCharsets.UTF_8))
                .build();
        String configured = rabbit.determineVirtualHost();
        this.vhost = configured == null || configured.isBlank() ? "/" : configured; // "/" es el vhost por defecto
    }

    // ------------------------------------------------------------------ modelos de la API HTTP

    public record QueueDetails(String name, boolean durable, @JsonProperty("auto_delete") boolean autoDelete,
                               Map<String, Object> arguments, Integer messages, Integer consumers) {
    }

    public record ExchangeDetails(String name, String type, boolean durable,
                                  @JsonProperty("auto_delete") boolean autoDelete, boolean internal) {
    }

    public record BindingDetails(String source, String destination,
                                 @JsonProperty("destination_type") String destinationType,
                                 @JsonProperty("routing_key") String routingKey) {
    }

    // ------------------------------------------------------------------ consultas

    public List<QueueDetails> listQueues() {
        return get("/api/queues/{vhost}", new ParameterizedTypeReference<>() {}, vhost);
    }

    public List<ExchangeDetails> listExchanges() {
        return get("/api/exchanges/{vhost}", new ParameterizedTypeReference<>() {}, vhost);
    }

    public List<BindingDetails> listBindings() {
        return get("/api/bindings/{vhost}", new ParameterizedTypeReference<>() {}, vhost);
    }

    /** Configuración actual de una cola, o vacío si no existe. */
    public Optional<QueueDetails> getQueue(String name) {
        return getIfExists("/api/queues/{vhost}/{name}", QueueDetails.class, vhost, name);
    }

    /** Configuración actual de un exchange, o vacío si no existe. */
    public Optional<ExchangeDetails> getExchange(String name) {
        return getIfExists("/api/exchanges/{vhost}/{name}", ExchangeDetails.class, vhost, name);
    }

    /** Bindings exchange -> cola entre esos dos recursos (puede haber varios con distinta routing key). */
    public List<BindingDetails> getBindings(String exchange, String queue) {
        return get("/api/bindings/{vhost}/e/{exchange}/q/{queue}", new ParameterizedTypeReference<>() {},
                vhost, exchange, queue);
    }

    private <T> List<T> get(String uri, ParameterizedTypeReference<List<T>> type, Object... vars) {
        List<T> body = restClient.get().uri(uri, vars).retrieve().body(type);
        return body == null ? List.of() : body;
    }

    private <T> Optional<T> getIfExists(String uri, Class<T> type, Object... vars) {
        try {
            return Optional.ofNullable(restClient.get().uri(uri, vars).retrieve().body(type));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                return Optional.empty();
            }
            throw e;
        }
    }
}
