package com.charmeetchic.api.streaming.consumer;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Valida (Bean Validation) cada evento recibido ANTES de que llegue a la lógica de negocio. Un evento inválido
 * lanza {@link InvalidStreamingEventException}, que el manejador de errores trata como no recuperable (.DLT
 * directo). Así un productor defectuoso no puede corromper las métricas ni el stock en memoria.
 */
@Component
public class StreamingEventValidator {

    private final Validator validator;

    public StreamingEventValidator(Validator validator) {
        this.validator = validator;
    }

    public <T> T validate(T event) {
        if (event == null) {
            // Mensaje sin valor (null) donde se esperaba un evento: tampoco se arregla reintentando.
            throw new InvalidStreamingEventException("Evento", List.of("el mensaje no trae valor (null)"));
        }
        Set<ConstraintViolation<T>> violations = validator.validate(event);
        if (!violations.isEmpty()) {
            List<String> errors = violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .sorted()
                    .toList();
            throw new InvalidStreamingEventException(event.getClass().getSimpleName(), errors);
        }
        return event;
    }
}
