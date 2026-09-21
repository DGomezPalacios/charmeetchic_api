package com.charmeetchic.api.service;

import com.charmeetchic.api.config.JwtConfig;
import com.charmeetchic.api.exception.InvalidJwtException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Obtiene (y cachea) las claves públicas RSA con las que Google firma los ID tokens de Firebase
 * (JWKS de {@code securetoken@system.gserviceaccount.com}). jjwt no descarga JWKS por sí mismo,
 * por eso existe esta clase. Son claves PÚBLICAS: no requieren credenciales ni cuenta de servicio.
 *
 * <p>Comportamiento:
 * <ul>
 *   <li>La caché se consulta por {@code kid} (id de clave que viene en la cabecera del token).</li>
 *   <li>Si llega un {@code kid} desconocido se recarga el JWKS (Google rota sus claves periódicamente), pero como
 *       máximo una vez cada {@code jwksMinRefreshSeconds}: así un atacante que envíe tokens con
 *       {@code kid} inventados no puede provocar una descarga por petición.</li>
 * </ul>
 */
@Slf4j
@Component
public class JwksKeyProvider {

    private final JwtConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private volatile Map<String, PublicKey> keysByKid = Map.of();
    private volatile Instant lastRefresh = Instant.EPOCH;

    public JwksKeyProvider(JwtConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /**
     * @param kid identificador de clave de la cabecera del JWT
     * @return la clave pública correspondiente
     * @throws InvalidJwtException si el token no trae {@code kid} o Google no publica esa clave
     */
    public PublicKey getKey(String kid) {
        if (kid == null || kid.isBlank()) {
            throw new InvalidJwtException("El token no indica la clave de firma (kid)");
        }
        PublicKey key = keysByKid.get(kid);
        if (key == null) {
            refresh();
            key = keysByKid.get(kid);
        }
        if (key == null) {
            throw new InvalidJwtException("Clave de firma desconocida para este token");
        }
        return key;
    }

    private synchronized void refresh() {
        Instant now = Instant.now();
        if (lastRefresh.plusSeconds(config.getJwksMinRefreshSeconds()).isAfter(now)) {
            return; // recarga reciente: se respeta el intervalo mínimo
        }
        lastRefresh = now;
        String uri = config.jwksUri();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("JWKS respondió HTTP " + response.statusCode());
            }
            keysByKid = parse(response.body());
            log.info("JWKS de Firebase (Google) recargado: {} claves", keysByKid.size());
        } catch (IOException | GeneralSecurityException | IllegalStateException e) {
            log.error("No se pudo cargar el JWKS desde {}", uri, e);
            throw new IllegalStateException("No se pudieron obtener las claves de firma de Firebase", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Carga de JWKS interrumpida", e);
        }
    }

    private Map<String, PublicKey> parse(String json) throws IOException, GeneralSecurityException {
        Map<String, PublicKey> result = new HashMap<>();
        KeyFactory factory = KeyFactory.getInstance("RSA");
        Base64.Decoder decoder = Base64.getUrlDecoder();
        for (JsonNode jwk : objectMapper.readTree(json).path("keys")) {
            if (!"RSA".equals(jwk.path("kty").asText()) || !jwk.hasNonNull("kid")) {
                continue;
            }
            BigInteger modulus = new BigInteger(1, decoder.decode(jwk.path("n").asText()));
            BigInteger exponent = new BigInteger(1, decoder.decode(jwk.path("e").asText()));
            result.put(jwk.get("kid").asText(), factory.generatePublic(new RSAPublicKeySpec(modulus, exponent)));
        }
        return Map.copyOf(result);
    }
}
