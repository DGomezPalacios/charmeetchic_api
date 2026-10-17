package com.charmeetchic.rabbitadmin.exception;

import com.charmeetchic.rabbitadmin.dto.ApiError;
import com.charmeetchic.rabbitadmin.exception.AdminExceptions.BrokerUnavailableException;
import com.charmeetchic.rabbitadmin.exception.AdminExceptions.ConflictException;
import com.charmeetchic.rabbitadmin.exception.AdminExceptions.NotFoundException;
import com.charmeetchic.rabbitadmin.exception.AdminExceptions.ProtectedResourceException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/** Traduce excepciones a respuestas {@link ApiError} con el código HTTP correcto. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 400 por Bean Validation. {@link BindException} cubre los dos casos: cuerpo JSON ({@code @RequestBody},
     * su subclase MethodArgumentNotValidException) y parámetros de la URL (DELETE /bindings?...).
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiError> validation(BindException ex, HttpServletRequest req) {
        Map<String, String> details = new TreeMap<>();
        for (ObjectError error : ex.getBindingResult().getAllErrors()) {
            String field = error instanceof FieldError fe ? fe.getField() : error.getObjectName();
            details.merge(field, error.getDefaultMessage(), (a, b) -> a + "; " + b);
        }
        return build(HttpStatus.BAD_REQUEST, "Datos inválidos: " + String.join(" | ", details.values()), req, details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "El cuerpo de la solicitud falta o no es un JSON válido", req, null);
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

    /** 503: RabbitMQ (AMQP) o su API de administración (HTTP) no responden. */
    @ExceptionHandler({BrokerUnavailableException.class, AmqpConnectException.class, ResourceAccessException.class})
    public ResponseEntity<ApiError> unavailable(RuntimeException ex, HttpServletRequest req) {
        log.error("RabbitMQ no disponible en {}: {}", req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.SERVICE_UNAVAILABLE, "RabbitMQ no está disponible; intenta de nuevo en unos segundos",
                req, null);
    }

    /** 502: la API de administración respondió con error (p. ej. 401 por usuario/clave de RabbitMQ incorrectos). */
    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<ApiError> badGateway(RestClientResponseException ex, HttpServletRequest req) {
        log.error("La API de administración de RabbitMQ respondió {} en {}", ex.getStatusCode(), req.getRequestURI());
        return build(HttpStatus.BAD_GATEWAY, "La API de administración de RabbitMQ respondió "
                + ex.getStatusCode().value() + "; revisa RABBITMQ_MANAGEMENT_URL y las credenciales", req, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest req) {
        log.error("Error inesperado en {}", req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servidor", req, null);
    }

    private static ResponseEntity<ApiError> build(HttpStatus status, String message, HttpServletRequest req,
                                                  Map<String, String> details) {
        return ResponseEntity.status(status).body(new ApiError(Instant.now(), status.value(),
                status.getReasonPhrase(), message, req.getRequestURI(), details));
    }
}
