package com.charmeetchic.gateway;

import com.charmeetchic.gateway.security.FirebaseTokenValidators;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gateway real (Netty + Spring Cloud Gateway + Spring Security) contra dos servidores HTTP falsos que hacen de
 * backend y de rabbit-admin. Así se comprueba qué llega (y qué NO llega) a cada servicio.
 * Los tokens se firman con una clave RSA de prueba y se validan con las reglas de producción.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.cors.allowed-origins=https://charme-et-chic.web.app,http://localhost:4200"
})
class GatewayIntegrationTest {

    private static final String PROJECT = "charme-et-chic";
    private static final String FRONT = "https://charme-et-chic.web.app";
    private static final KeyPair KEYS = rsaKeyPair();
    private static final KeyPair OTHER_KEYS = rsaKeyPair();

    private static final MockWebServer backend = startServer("{\"from\":\"backend\"}");
    private static final MockWebServer rabbitAdmin = startServer("{\"from\":\"rabbit-admin\"}");

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry registry) {
        registry.add("BACKEND_URL", () -> "http://localhost:" + backend.getPort());
        registry.add("RABBIT_ADMIN_URL", () -> "http://localhost:" + rabbitAdmin.getPort());
    }

    @TestConfiguration
    static class TestJwtConfig {
        @Bean
        @Primary
        ReactiveJwtDecoder testJwtDecoder() {
            NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                    .withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
            decoder.setJwtValidator(FirebaseTokenValidators.forProject(PROJECT));
            return decoder;
        }
    }

    @Autowired
    private WebTestClient client;

    @BeforeEach
    void drainRecordedRequests() throws InterruptedException {
        while (backend.takeRequest(10, TimeUnit.MILLISECONDS) != null) { /* vaciar */ }
        while (rabbitAdmin.takeRequest(10, TimeUnit.MILLISECONDS) != null) { /* vaciar */ }
    }

    @AfterAll
    static void shutdown() throws IOException {
        backend.shutdown();
        rabbitAdmin.shutdown();
    }

    // ------------------------------------------------------------------ 401 antes de llegar al backend

    @Test
    void sinToken_es401YNoLlegaAlBackend() throws Exception {
        client.get().uri("/api/products").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectBody().jsonPath("$.status").isEqualTo(401);
        assertThat(backend.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void tokenFirmadoConOtraClave_es401YNoLlegaAlBackend() throws Exception {
        client.get().uri("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + sign(claims(c -> { }),
                        (RSAPrivateKey) OTHER_KEYS.getPrivate()))
                .exchange().expectStatus().isUnauthorized();
        assertThat(backend.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void tokenDeOtroProyecto_es401() throws Exception {
        client.get().uri("/admin/queues").header(HttpHeaders.AUTHORIZATION, bearer(c -> c.audience("otro-proyecto")))
                .exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.message").value(m -> assertThat((String) m).contains("aud"));
        assertThat(rabbitAdmin.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void tokenVencido_es401() throws Exception {
        client.get().uri("/api/orders").header(HttpHeaders.AUTHORIZATION, bearer(c -> c
                        .issueTime(Date.from(Instant.now().minusSeconds(7200)))
                        .expirationTime(Date.from(Instant.now().minusSeconds(3600)))))
                .exchange().expectStatus().isUnauthorized();
        assertThat(backend.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }

    // ------------------------------------------------------------------ enrutamiento

    @Test
    void apiConTokenValido_vaAlBackendConElMismoTokenYSinCambiarLaRuta() throws Exception {
        String token = bearer(c -> { });
        client.get().uri("/api/products?page=0").header(HttpHeaders.AUTHORIZATION, token).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.from").isEqualTo("backend");

        RecordedRequest forwarded = backend.takeRequest(1, TimeUnit.SECONDS);
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.getPath()).isEqualTo("/api/products?page=0");
        assertThat(forwarded.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo(token); // el backend vuelve a validar
        assertThat(forwarded.getHeader("X-Forwarded-For")).isNotBlank();
    }

    @Test
    void adminConTokenValido_vaARabbitAdminSinElPrefijoAdmin() throws Exception {
        client.delete().uri("/admin/queues/demo.cola?ifEmpty=true").header(HttpHeaders.AUTHORIZATION, bearer(c -> { }))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.from").isEqualTo("rabbit-admin");

        RecordedRequest forwarded = rabbitAdmin.takeRequest(1, TimeUnit.SECONDS);
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.getMethod()).isEqualTo("DELETE");
        assertThat(forwarded.getPath()).isEqualTo("/queues/demo.cola?ifEmpty=true");
        assertThat(backend.takeRequest(100, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void healthDelBackend_pasaSinToken() throws Exception {
        client.get().uri("/api/health").exchange().expectStatus().isOk();
        assertThat(backend.takeRequest(1, TimeUnit.SECONDS).getPath()).isEqualTo("/api/health");
    }

    @Test
    void healthDelGateway_respondeElPropioGateway() {
        client.get().uri("/health").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.service").isEqualTo("charmeetchic-gateway");
    }

    @Test
    void rutaQueNoExiste_conToken_es404() {
        client.get().uri("/otra-cosa").header(HttpHeaders.AUTHORIZATION, bearer(c -> { }))
                .exchange().expectStatus().isNotFound();
    }

    // ------------------------------------------------------------------ CORS

    @Test
    void preflightDelFront_seRespondeEnElGatewaySinToken() throws Exception {
        client.options().uri("/api/orders")
                .header(HttpHeaders.ORIGIN, FRONT)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONT);
        assertThat(backend.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void preflightDeOrigenNoPermitido_es403() {
        client.options().uri("/api/orders")
                .header(HttpHeaders.ORIGIN, "https://sitio-malicioso.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void localhost4200_tambienEstaPermitido() {
        client.options().uri("/api/products")
                .header(HttpHeaders.ORIGIN, "http://localhost:4200")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:4200");
    }

    @Test
    void peticionRealDelFront_unSoloAllowOriginYElBackendNoRecibeOrigin() throws Exception {
        client.get().uri("/api/products").header(HttpHeaders.ORIGIN, FRONT)
                .header(HttpHeaders.AUTHORIZATION, bearer(c -> { }))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONT) // exactamente uno
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "Location");

        RecordedRequest forwarded = backend.takeRequest(1, TimeUnit.SECONDS);
        assertThat(forwarded.getHeader(HttpHeaders.ORIGIN)).isNull();
    }

    // ------------------------------------------------------------------ helpers

    private static String bearer(Consumer<JWTClaimsSet.Builder> customizer) {
        return "Bearer " + sign(claims(customizer), (RSAPrivateKey) KEYS.getPrivate());
    }

    private static JWTClaimsSet claims(Consumer<JWTClaimsSet.Builder> customizer) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer("https://securetoken.google.com/" + PROJECT)
                .audience(PROJECT)
                .subject("uid-test")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)));
        customizer.accept(builder);
        return builder.build();
    }

    private static String sign(JWTClaimsSet claims, RSAPrivateKey key) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static MockWebServer startServer(String body) {
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
            }
        });
        try {
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return server;
    }

    private static KeyPair rsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
