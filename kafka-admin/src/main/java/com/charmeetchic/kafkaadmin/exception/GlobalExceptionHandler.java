package com.charmeetchic.kafkaadmin.exception;

import com.charmeetchic.kafkaadmin.dto.ApiError;
import com.charmeetchic.kafkaadmin.dto.TopicConfigs;
import com.charmeetchic.kafkaadmin.exception.AdminExceptions.BadRequestException;
import com.charmeetchic.kafkaadmin.exception.AdminExceptions.BrokerUnavailableException;
import com.charmeetchic.kafkaadmin.exception.AdminExceptions.ConflictException;
import com.charmeetchic.kafkaadmin.exception.AdminExceptions.NotFoundException;
import com.charmeetchic.kafkaadmin.exception.AdminExceptions.ProtectedResourceException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/** Traduce excepciones a respuestas {@link ApiError} con el código HTTP correcto (mismo formato que rabbit-admin). */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 400 por Bean Validation del cuerpo JSON. */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiError> validation(BindException ex, HttpServletRequest req) {
        Map<String, String> details = new TreeMap<>();
        for (ObjectError error : ex.getBindingResult().getAllErrors()) {
            String field = error instanceof FieldError fe ? fe.getField() : error.getObjectName();
            String message = error.getDefaultMessage();
            if (field.equals("configsValid")) {
                // Detalle concreto de qué config falló y por qué.
                Object target = ex.getBindingResult().getTarget();
                message = String.join("; ", TopicConfigs.errors(configsOf(target)));
            }
            details.merge(field, message, (a, b) -> a + "; " + b);
        }
        return build(HttpStatus.BAD_REQUEST, "Datos inválidos: " + String.join(" | ", details.values()), req, details);
    }

    /** 400 por validación de parámetros de la URL (nombre de tópico, groupId). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> pathValidation(ConstraintViolationException ex, HttpServletRequest req) {
        Map<String, String> details = new TreeMap<>();
        ex.getConstraintViolations().forEach(v -> details.put(v.getPropertyPath().toString(), v.getMessage()));
        return build(HttpStatus.BAD_REQUEST, "Parámetros inválidos", req, details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "El cuerpo de la solicitud falta o no es un JSON válido", req, null);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiError> badRequest(BadRequestException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), req, null);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> notFound(NotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), req, null);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> conflict(ConflictException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), req, null);
    }

    @ExceptionHandler(ProtectedResourceException.class)
    public ResponseEntity<ApiError> forbidden(ProtectedResourceException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, ex.getMessage(), req, null);
    }

    /** 503: el clúster no responde. */
    @ExceptionHandler(BrokerUnavailableException.class)
    public ResponseEntity<ApiError> unavailable(BrokerUnavailableException ex, HttpServletRequest req) {
        log.error("Kafka no disponible en {}: {}", req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), req, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest req) {
        log.error("Error inesperado en {}", req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servidor", req, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> configsOf(Object target) {
        try {
            return (Map<String, String>) target.getClass().getMethod("configs").invoke(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Map.of();
        }
    }

    private static ResponseEntity<ApiError> build(HttpStatus status, String message, HttpServletRequest req,
                                                  Map<String, String> details) {
        return ResponseEntity.status(status).body(new ApiError(Instant.now(), status.value(),
                status.getReasonPhrase(), message, req.getRequestURI(), details));
    }
}
