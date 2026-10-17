package com.charmeetchic.rabbitadmin.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Bean Validation de los DTOs: nombres vacíos o inválidos y configuraciones sin sentido. */
class DtoValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void colaValida_conValoresPorDefecto() {
        QueueRequest q = new QueueRequest("pedidos.web", null, null, null, null, null, null);

        assertThat(validator.validate(q)).isEmpty();
        assertThat(q.durable()).isTrue();
        assertThat(q.autoDelete()).isFalse();
    }

    @Test
    void nombreDeColaVacio() {
        assertThat(messages(new QueueRequest("  ", null, null, null, null, null, null)))
                .contains("el nombre de la cola es obligatorio");
    }

    @Test
    void nombreDeColaConCaracteresInvalidos() {
        for (String bad : List.of("con espacio", "-empieza-con-guion", "ñandú", "a/b", "a*b")) {
            assertThat(messages(new QueueRequest(bad, null, null, null, null, null, null)))
                    .as(bad).anyMatch(m -> m.startsWith("el nombre solo puede tener"));
        }
    }

    @Test
    void prefijoAmqReservado() {
        assertThat(messages(new QueueRequest("amq.mia", null, null, null, null, null, null)))
                .contains("el prefijo 'amq.' está reservado por RabbitMQ");
        assertThat(messages(new ExchangeRequest("amq.mio", "direct", null, null)))
                .contains("el prefijo 'amq.' está reservado por RabbitMQ");
    }

    @Test
    void nombreDemasiadoLargo() {
        assertThat(messages(new QueueRequest("a".repeat(256), null, null, null, null, null, null)))
                .contains("el nombre no puede superar 255 caracteres");
    }

    @Test
    void deadLetterRoutingKeySinExchange_esInvalida() {
        assertThat(messages(new QueueRequest("pedidos", null, null, null, "pedidos.dlq", null, null)))
                .contains("deadLetterRoutingKey requiere deadLetterExchange");
    }

    @Test
    void ttlYLongitudDebenSerPositivos() {
        assertThat(messages(new QueueRequest("pedidos", null, null, null, null, 0L, -1)))
                .contains("messageTtlMs debe ser mayor que 0", "maxLength debe ser mayor que 0");
    }

    @Test
    void tipoDeExchange_soloLosCuatroValidos() {
        for (String ok : List.of("direct", "TOPIC", "Fanout", "headers")) {
            assertThat(validator.validate(new ExchangeRequest("pedidos", ok, null, null))).as(ok).isEmpty();
        }
        assertThat(messages(new ExchangeRequest("pedidos", "x-delayed", null, null)))
                .contains("type debe ser direct, topic, fanout o headers");
        assertThat(messages(new ExchangeRequest("pedidos", null, null, null)))
                .contains("el tipo es obligatorio: direct, topic, fanout o headers");
    }

    @Test
    void tipoSeNormalizaAMinusculas() {
        assertThat(new ExchangeRequest("pedidos", " Topic ", null, null).type()).isEqualTo("topic");
    }

    @Test
    void bindingRequiereExchangeYCola() {
        assertThat(messages(new BindingRequest("", " ", "rk")))
                .contains("el exchange es obligatorio", "la cola es obligatoria");
    }

    @Test
    void bindingRoutingKey_aceptaComodinesYVacia() {
        assertThat(validator.validate(new BindingRequest("ex", "cola", "notificacion.reporte.*"))).isEmpty();
        assertThat(validator.validate(new BindingRequest("ex", "cola", "inventario.#"))).isEmpty();
        BindingRequest fanout = new BindingRequest("ex", "cola", null);
        assertThat(fanout.routingKey()).isEmpty();
        assertThat(validator.validate(fanout)).isEmpty();
    }

    @Test
    void bindingRoutingKeyInvalida() {
        assertThat(messages(new BindingRequest("ex", "cola", "con espacio")))
                .anyMatch(m -> m.startsWith("routingKey solo puede tener"));
    }

    private <T> List<String> messages(T dto) {
        return validator.validate(dto).stream().map(ConstraintViolation::getMessage).toList();
    }
}
