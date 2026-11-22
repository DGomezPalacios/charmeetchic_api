package com.charmeetchic.api.service;

import com.charmeetchic.api.config.JwtConfig;
import com.charmeetchic.api.exception.InvalidJwtException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Descarga y caché de las claves públicas con las que Google firma los tokens de Firebase.
 *
 * <p>Es la primera pieza de la validación de tokens: si devuelve una clave equivocada, o si se puede forzar a
 * descargar el JWKS en cada petición, la seguridad de toda la API queda comprometida. En el resto de las pruebas
 * esta clase se reemplaza por un mock; aquí se prueba la real contra un servidor HTTP local (JDK, sin red) que
 * juega el papel del endpoint JWKS de Google.
 */
class JwksKeyProviderTest {

    private final KeyPair googleKey = generateRsaKeyPair();
    private final AtomicInteger requests = new AtomicInteger();

    private HttpServer server;
    private volatile int status = 200;
    private volatile String body;

    @BeforeEach
    void startFakeGoogle() throws Exception {
        body = jwks(rsaJwk("kid-1", (RSAPublicKey) googleKey.getPublic()));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopFakeGoogle() {
        server.stop(0);
    }

    @Test
    void tokenWithoutKid_isRejectedWithoutDownloadingKeys() {
        JwksKeyProvider provider = provider(300);

        assertThatThrownBy(() -> provider.getKey(null)).isInstanceOf(InvalidJwtException.class);
        assertThatThrownBy(() -> provider.getKey("  ")).isInstanceOf(InvalidJwtException.class);
        assertThat(requests).hasValue(0);
    }

    @Test
    void knownKid_returnsGooglePublicKey_andCachesIt() {
        JwksKeyProvider provider = provider(0);

        assertThat(provider.getKey("kid-1")).isEqualTo(googleKey.getPublic());
        assertThat(provider.getKey("kid-1")).isEqualTo(googleKey.getPublic());

        assertThat(requests).as("la segunda consulta sale de la caché").hasValue(1);
    }

    @Test
    void invented_kids_cannotForceADownloadPerRequest() {
        JwksKeyProvider provider = provider(300);
        provider.getKey("kid-1"); // primera descarga

        for (int i = 0; i < 5; i++) {
            String fakeKid = "inventado-" + i;
            assertThatThrownBy(() -> provider.getKey(fakeKid))
                    .isInstanceOf(InvalidJwtException.class)
                    .hasMessageContaining("desconocida");
        }

        assertThat(requests).as("dentro del intervalo mínimo no se vuelve a descargar").hasValue(1);
    }

    @Test
    void rotatedKey_isPickedUpAfterTheMinimumInterval() {
        JwksKeyProvider provider = provider(0);
        provider.getKey("kid-1");

        KeyPair rotated = generateRsaKeyPair();
        body = jwks(rsaJwk("kid-2", (RSAPublicKey) rotated.getPublic()));

        assertThat(provider.getKey("kid-2")).isEqualTo(rotated.getPublic());
        assertThat(requests).hasValue(2);
    }

    @Test
    void nonRsaKeysAndKeysWithoutKid_areIgnored() {
        body = "{\"keys\":[" + "{\"kty\":\"EC\",\"kid\":\"kid-ec\",\"crv\":\"P-256\"},"
                + "{\"kty\":\"RSA\",\"n\":\"AQAB\",\"e\":\"AQAB\"},"
                + rsaJwk("kid-1", (RSAPublicKey) googleKey.getPublic()) + "]}";
        JwksKeyProvider provider = provider(300);

        assertThat(provider.getKey("kid-1")).isEqualTo(googleKey.getPublic());
        assertThatThrownBy(() -> provider.getKey("kid-ec")).isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void jwksUnavailable_isAServerError_notAnInvalidToken() {
        status = 503;
        JwksKeyProvider provider = provider(300);

        // Si Google no responde, el token no es "inválido": es un fallo nuestro (500), no un 401.
        assertThatThrownBy(() -> provider.getKey("kid-1"))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(InvalidJwtException.class)
                .hasMessageContaining("claves de firma");
    }

    // ------------------------------------------------------------------ helpers

    private JwksKeyProvider provider(long minRefreshSeconds) {
        String uri = "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
        JwtConfig config = new JwtConfig() {
            @Override
            public String jwksUri() {
                return uri;
            }
        };
        config.setJwksMinRefreshSeconds(minRefreshSeconds);
        return new JwksKeyProvider(config, new ObjectMapper());
    }

    private static String jwks(String... keys) {
        return "{\"keys\":[" + String.join(",", keys) + "]}";
    }

    private static String rsaJwk(String kid, RSAPublicKey key) {
        return "{\"kty\":\"RSA\",\"alg\":\"RS256\",\"use\":\"sig\",\"kid\":\"" + kid + "\","
                + "\"n\":\"" + base64Url(key.getModulus()) + "\",\"e\":\"" + base64Url(key.getPublicExponent()) + "\"}";
    }

    /** Entero sin signo en base64url sin relleno, como lo publica Google. */
    private static String base64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
