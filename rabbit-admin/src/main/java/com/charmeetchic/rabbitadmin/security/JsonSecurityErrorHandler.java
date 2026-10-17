package com.charmeetchic.rabbitadmin.security;

import com.charmeetchic.rabbitadmin.dto.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.time.Instant;

/**
 * Respuestas 401 y 403 en el mismo formato JSON ({@link ApiError}) que el resto de errores. Spring Security
 * por defecto responde estos códigos con el cuerpo vacío, lo que confunde al probar con Postman.
 */
@Slf4j
public class JsonSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public JsonSecurityErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 401: falta el token, está mal firmado, vencido, o es de otro proyecto. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        String reason = ex instanceof OAuth2AuthenticationException oauth && oauth.getError().getDescription() != null
                ? oauth.getError().getDescription()
                : ex.getMessage();
        log.debug("401 en {}: {}", request.getRequestURI(), reason);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(response, request, HttpStatus.UNAUTHORIZED, "Token ausente, inválido o expirado: " + reason);
    }

    /** 403: el token es válido pero el usuario no tiene el rol ADMIN. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        log.warn("403 en {}: usuario sin rol ADMIN", request.getRequestURI());
        write(response, request, HttpStatus.FORBIDDEN, "Se requiere el rol ADMIN para administrar RabbitMQ");
    }

    private void write(HttpServletResponse response, HttpServletRequest request, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), new ApiError(Instant.now(), status.value(),
                status.getReasonPhrase(), message, request.getRequestURI(), null));
    }
}
