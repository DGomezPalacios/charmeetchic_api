package com.charmeetchic.api;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Observabilidad (Evaluación 3): verifica que SOLO {@code /actuator/health} y {@code /actuator/prometheus} sean
 * públicos (Prometheus los lee sin token) y que el resto de Actuator siga cerrado, sin romper las reglas existentes.
 * MockMvc no aplica el context-path: en el servidor real las rutas son {@code /api/actuator/...}.
 */
@SpringBootTest
@AutoConfigureMockMvc
// En los tests Spring Boot apaga los exportadores de métricas (el registro de Prometheus no se crea y
// /actuator/prometheus daría 404); esta anotación los vuelve a encender como en la app real.
@AutoConfigureObservability
@TestPropertySource(properties = {
        "app.jwt.mode=local",
        "app.jwt.local-secret=" + ActuatorSecurityIntegrationTest.SECRET
})
class ActuatorSecurityIntegrationTest {

    // Mismo secreto que ApiIntegrationTest (los tokens de prueba se firman igual).
    static final String SECRET = "integration-test-secret-integration-test-1234";

    @Autowired
    private MockMvc mvc;

    @Test
    void actuatorHealth_isPublic_withoutDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")))
                // show-details: never -> un cliente anónimo no ve el estado de la base ni del disco
                .andExpect(content().string(not(containsString("components"))));
    }

    @Test
    void actuatorPrometheus_isPublic_andHasHttpHistogramWithApplicationLabel() throws Exception {
        // Una petición previa para que existan series de http.server.requests
        mvc.perform(get("/health")).andExpect(status().isOk());

        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("http_server_requests_seconds_bucket")))
                .andExpect(content().string(containsString("application=\"charmeetchic-api\"")))
                // Bucket del SLO de 300 ms (umbral de la alerta de latencia p95)
                .andExpect(content().string(containsString("le=\"0.3\"")))
                // El temporizador de peticiones en curso no publica buckets
                .andExpect(content().string(not(containsString("http_server_requests_active_seconds_bucket"))))
                .andExpect(content().string(containsString("jvm_memory_used_bytes")));
    }

    @Test
    void otherActuatorEndpoints_requireAuthentication() throws Exception {
        mvc.perform(get("/actuator")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isUnauthorized());
        // Solo GET es público: un POST a la misma ruta exige token
        mvc.perform(post("/actuator/prometheus")).andExpect(status().isUnauthorized());
    }

    @Test
    void otherActuatorEndpoints_areNotExposed_evenForAdmin() throws Exception {
        String admin = token("admin-actuator", "ADMIN");
        mvc.perform(get("/actuator/env").header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());
        mvc.perform(get("/actuator/heapdump").header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());
    }

    @Test
    void existingRules_stillApply() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk());
        mvc.perform(get("/products")).andExpect(status().isOk());
        mvc.perform(get("/products/low-stock")).andExpect(status().isUnauthorized());
        mvc.perform(get("/products/low-stock").header("Authorization", "Bearer " + token("user-actuator", "USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/reports/sales")).andExpect(status().isUnauthorized());
        mvc.perform(get("/profile")).andExpect(status().isUnauthorized());
    }

    private static String token(String uid, String... roles) {
        return Jwts.builder()
                .subject(uid)
                .claim("email", uid + "@charme.cl").claim("name", "Usuario " + uid)
                .claim("roles", List.of(roles))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
