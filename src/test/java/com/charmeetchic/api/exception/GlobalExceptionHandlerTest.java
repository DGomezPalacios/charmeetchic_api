package com.charmeetchic.api.exception;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.context.request.ServletWebRequest;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Formato de error uniforme ({@link ApiError}) para los casos que no se alcanzan fácilmente por HTTP en las
 * pruebas de integración. Lo crítico: los errores internos (500) y de base de datos (409) NO deben exponer al
 * cliente detalles técnicos (SQL, nombres de tablas, mensajes de excepción).
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/products");

    @Test
    void unexpectedError_returns500_withoutLeakingInternalDetails() {
        ResponseEntity<Object> response = handler.handleGeneric(
                new NullPointerException("password=secreta en ProductService:42"), request);

        ApiError body = body(response);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(body.message()).isEqualTo("Error interno del servidor");
        assertThat(body.message()).doesNotContain("password", "ProductService");
        assertThat(body.path()).isEqualTo("/products");
    }

    @Test
    void dataIntegrityViolation_returns409_withoutExposingSql() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException("could not execute statement",
                new SQLException("Unique index violation: PUBLIC.PRODUCTS(SKU) VALUES ('ANI-001')"));

        ResponseEntity<Object> response = handler.handleDataIntegrity(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(response).message()).doesNotContain("PRODUCTS", "SKU", "statement");
    }

    @Test
    void constraintViolationOnParameters_returns400_withFieldErrors() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        ConstraintViolationException ex = new ConstraintViolationException(validator.validate(new Params(-5)));

        ResponseEntity<Object> response = handler.handleConstraintViolation(ex, request);

        ApiError body = body(response);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body.fieldErrors()).containsKey("limit");
    }

    @Test
    void wrongHttpMethod_returns405_inTheStandardFormat() throws Exception {
        ResponseEntity<Object> response = handler.handleException(
                new HttpRequestMethodNotSupportedException("PATCH", List.of("GET", "POST")),
                new ServletWebRequest(request));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(body(response).message()).isEqualTo("Método HTTP no permitido para este recurso");
    }

    @Test
    void unsupportedContentType_returns415_inTheStandardFormat() throws Exception {
        ResponseEntity<Object> response = handler.handleException(
                new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)),
                new ServletWebRequest(request));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(body(response).message()).isEqualTo("Tipo de contenido no soportado");
        assertThat(body(response).path()).isEqualTo("/products");
    }

    private static ApiError body(ResponseEntity<Object> response) {
        assertThat(response.getBody()).isInstanceOf(ApiError.class);
        return (ApiError) response.getBody();
    }

    private record Params(@Min(1) int limit) {
    }
}
