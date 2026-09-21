package com.charmeetchic.api.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manejo centralizado de errores: convierte cualquier excepción en un {@link ApiError}.
 *
 * <p>Extiende {@link ResponseEntityExceptionHandler}, que ya cubre las excepciones estándar de
 * Spring MVC (404 de ruta inexistente, 405, 415, parámetros faltantes...). Aquí se fuerza que
 * TODAS usen el formato {@link ApiError} y se añaden las excepciones del dominio.
 *
 * <p>También lo usan los filtros de seguridad (vía {@code HandlerExceptionResolver}) para que un
 * 401/403 generado fuera de un controlador tenga exactamente el mismo formato.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    // ------------------------------------------------------------------ dominio

    @ExceptionHandler(InvalidJwtException.class)
    public ResponseEntity<Object> handleInvalidJwt(InvalidJwtException ex, HttpServletRequest req) {
        log.warn("401 {} {} -> {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.UNAUTHORIZED, ex.getMessage(), req.getRequestURI(), null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        log.warn("403 {} {} -> {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.FORBIDDEN, "No tienes permisos para realizar esta operación",
                req.getRequestURI(), null);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Object> handleNotFound(ResourceNotFoundException ex, HttpServletRequest req) {
        log.debug("404 {} {} -> {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), req.getRequestURI(), null);
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Object> handleValidation(ValidationException ex, HttpServletRequest req) {
        log.debug("400 {} {} -> {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), req.getRequestURI(), null);
    }

    @ExceptionHandler({InsufficientStockException.class, ConflictException.class})
    public ResponseEntity<Object> handleConflict(RuntimeException ex, HttpServletRequest req) {
        log.warn("409 {} {} -> {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage(), req.getRequestURI(), null);
    }

    // ------------------------------------------------------------------ persistencia

    /** Alguien modificó el mismo registro entre la lectura y la escritura (campo {@code @Version}). */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Object> handleOptimisticLock(ObjectOptimisticLockingFailureException ex,
                                                       HttpServletRequest req) {
        log.warn("409 (bloqueo optimista) {} {} -> {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT,
                "El recurso fue modificado por otra operación. Vuelve a consultarlo e inténtalo de nuevo.",
                req.getRequestURI(), null);
    }

    /** Última línea de defensa ante violaciones de unicidad / FK que escapen a las validaciones. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleDataIntegrity(DataIntegrityViolationException ex,
                                                      HttpServletRequest req) {
        log.warn("409 (integridad) {} {} -> {}", req.getMethod(), req.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return build(HttpStatus.CONFLICT, "La operación viola una restricción de integridad de datos",
                req.getRequestURI(), null);
    }

    // ------------------------------------------------------------------ validación / entrada

    /** Violaciones de {@code @Validated} en parámetros de método / @RequestParam. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex,
                                                            HttpServletRequest req) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(v -> errors.put(v.getPropertyPath().toString(), v.getMessage()));
        return build(HttpStatus.BAD_REQUEST, "Parámetros inválidos", req.getRequestURI(), errors);
    }

    /** Ej.: {@code ?sort=campoInexistente,asc}. */
    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<Object> handleBadSortProperty(PropertyReferenceException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "Propiedad de ordenamiento inválida: " + ex.getPropertyName(),
                req.getRequestURI(), null);
    }

    /** Ej.: {@code /products/abc} (id no numérico) o {@code ?from=31-12-2025} (fecha mal formada). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                     HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST,
                "Valor inválido para el parámetro '" + ex.getName() + "'", req.getRequestURI(), null);
    }

    /** Cuerpo del request con errores de validación de Bean Validation ({@code @Valid}). */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        ex.getBindingResult().getGlobalErrors()
                .forEach(ge -> errors.putIfAbsent(ge.getObjectName(), ge.getDefaultMessage()));
        return build(HttpStatus.BAD_REQUEST, "Datos de entrada inválidos", path(request), errors);
    }

    /** Violaciones de {@code @Min/@Max...} directamente sobre {@code @RequestParam}/{@code @PathVariable}. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getAllValidationResults().forEach(result -> result.getResolvableErrors().forEach(
                error -> errors.putIfAbsent(result.getMethodParameter().getParameterName(),
                        error.getDefaultMessage())));
        return build(HttpStatus.BAD_REQUEST, "Parámetros inválidos", path(request), errors);
    }

    /** JSON mal formado / enum desconocido. No se filtra el detalle técnico de Jackson. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        log.debug("Cuerpo ilegible: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "El cuerpo de la solicitud es inválido o está mal formado",
                path(request), null);
    }

    /** Unifica el formato de las demás excepciones estándar de Spring MVC (404, 405, 415...). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.valueOf(statusCode.value());
        String message = status.is4xxClientError() ? messageFor(status, ex) : "Error interno del servidor";
        if (status.is5xxServerError()) {
            log.error("Error {} en {}", status.value(), path(request), ex);
        }
        return ResponseEntity.status(status).headers(headers).body(
                new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message, path(request), null));
    }

    // ------------------------------------------------------------------ fallback

    /** Cualquier otra excepción: se registra completa en el log pero no se expone al cliente. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleGeneric(Exception ex, HttpServletRequest req) {
        log.error("500 {} {}", req.getMethod(), req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servidor", req.getRequestURI(), null);
    }

    // ------------------------------------------------------------------ helpers

    private ResponseEntity<Object> build(HttpStatus status, String message, String path,
                                         Map<String, String> fieldErrors) {
        ApiError body = new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message, path,
                fieldErrors);
        return ResponseEntity.status(status).body(body);
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : null;
    }

    private static String messageFor(HttpStatus status, Exception ex) {
        return switch (status) {
            case NOT_FOUND -> "Recurso no encontrado";
            case METHOD_NOT_ALLOWED -> "Método HTTP no permitido para este recurso";
            case UNSUPPORTED_MEDIA_TYPE -> "Tipo de contenido no soportado";
            case NOT_ACCEPTABLE -> "Formato de respuesta no aceptable";
            default -> ex.getMessage();
        };
    }
}
