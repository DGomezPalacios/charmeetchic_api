package com.charmeetchic.api;

import com.charmeetchic.api.exception.InvalidJwtException;
import com.charmeetchic.api.service.JwksKeyProvider;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cadena de seguridad completa en modo FIREBASE (el de producción): tokens RS256 con issuer
 * {@code https://securetoken.google.com/<PROJECT_ID>} y audience {@code <PROJECT_ID>}.
 *
 * <p>La descarga de las claves públicas de Google se simula ({@link MockBean} de {@link JwksKeyProvider}):
 * el par RSA generado aquí hace de "Google". Así se comprueba filtro + servicio + reglas de acceso sin red
 * ni un proyecto real de Firebase. Usa su propia base H2 para no interferir con otros tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.jwt.mode=firebase",
        "firebase.project-id=" + FirebaseAuthIntegrationTest.PROJECT_ID,
        "spring.datasource.url=jdbc:h2:mem:firebasetestdb"
})
class FirebaseAuthIntegrationTest {

    static final String PROJECT_ID = "charmeetchic-it";
    private static final String ISSUER = "https://securetoken.google.com/" + PROJECT_ID;
    private static final KeyPair GOOGLE_KEYS = rsaKeyPair();

    @Autowired
    private MockMvc mvc;
    @MockBean
    private JwksKeyProvider jwksKeyProvider;

    @BeforeEach
    void googlePublishesOnlyItsOwnKey() {
        when(jwksKeyProvider.getKey(anyString()))
                .thenThrow(new InvalidJwtException("Clave de firma desconocida para este token"));
        // doReturn(...).when(...): con when(mock.getKey(..)) se invocaría el stub genérico de arriba y lanzaría.
        doReturn(GOOGLE_KEYS.getPublic()).when(jwksKeyProvider).getKey("kid-google");
    }

    @Test
    void publicEndpoints_workWithoutToken() throws Exception {
        mvc.perform(get("/products")).andExpect(status().isOk());
        mvc.perform(get("/health")).andExpect(status().isOk());
    }

    @Test
    void validFirebaseToken_asUser_canReadProfile_butNotAdminEndpoints() throws Exception {
        String token = firebaseToken("firebase-uid-123").claim("email", "ana@charme.cl").claim("name", "Ana").compact();

        mvc.perform(auth(get("/profile"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is("firebase-uid-123")))
                .andExpect(jsonPath("$.email", is("ana@charme.cl")))
                .andExpect(jsonPath("$.roles", hasItem("USER")));

        mvc.perform(auth(get("/reports/inventory-value"), token)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/orders"), token)).andExpect(status().isOk());
    }

    @Test
    void firebaseToken_withAdminCustomClaim_reachesAdminEndpoints() throws Exception {
        String viaRolesArray = firebaseToken("admin-1").claim("roles", List.of("ADMIN")).compact();
        String viaAdminFlag = firebaseToken("admin-2").claim("admin", true).compact();

        mvc.perform(auth(get("/reports/inventory-value"), viaRolesArray)).andExpect(status().isOk());
        mvc.perform(auth(get("/products/low-stock"), viaAdminFlag)).andExpect(status().isOk());
    }

    /** Falsificación clásica: claims perfectos (issuer, audience, ADMIN) pero firmados con una clave que no es de Google. */
    @Test
    void forgedToken_isRejected_evenWithPerfectClaims() throws Exception {
        String forged = builder("admin-uid").claim("roles", List.of("ADMIN"))
                .signWith(rsaKeyPair().getPrivate(), Jwts.SIG.RS256).compact();

        mvc.perform(auth(get("/reports/inventory-value"), forged))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status", is(401)));
    }

    @Test
    void tokenWithoutSignature_isRejected() throws Exception {
        String unsigned = Jwts.builder().header().keyId("kid-google").and()
                .issuer(ISSUER).audience().add(PROJECT_ID).and().subject("admin-uid")
                .claim("roles", List.of("ADMIN")).expiration(inSeconds(3600)).compact();

        mvc.perform(auth(get("/reports/inventory-value"), unsigned)).andExpect(status().isUnauthorized());
    }

    @Test
    void hmacToken_isRejected() throws Exception {
        String hs256 = builder("admin-uid").claim("roles", List.of("ADMIN"))
                .signWith(Keys.hmacShaKeyFor("un-secreto-cualquiera-de-mas-de-32-bytes!!".getBytes(StandardCharsets.UTF_8)))
                .compact();

        mvc.perform(auth(get("/reports/inventory-value"), hs256))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", containsString("Algoritmo")));
    }

    @Test
    void tokenFromAnotherFirebaseProject_isRejected() throws Exception {
        String otherProject = Jwts.builder().header().keyId("kid-google").and()
                .issuer("https://securetoken.google.com/otro-proyecto").audience().add("otro-proyecto").and()
                .subject("uid").expiration(inSeconds(3600))
                .signWith(GOOGLE_KEYS.getPrivate(), Jwts.SIG.RS256).compact();

        mvc.perform(auth(get("/profile"), otherProject))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", containsString("proyecto de Firebase")));
    }

    @Test
    void expiredToken_isRejected_withRenewHint() throws Exception {
        String expired = firebaseToken("uid", -3600).compact();

        mvc.perform(auth(get("/profile"), expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", containsString("getIdToken")));
    }

    @Test
    void tokenWithUnknownKeyId_isRejected() throws Exception {
        String token = builder("uid").header().keyId("kid-desconocido").and()
                .signWith(GOOGLE_KEYS.getPrivate(), Jwts.SIG.RS256).compact();

        mvc.perform(auth(get("/profile"), token)).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ helpers

    private JwtBuilder firebaseToken(String uid) {
        return firebaseToken(uid, 3600);
    }

    private JwtBuilder firebaseToken(String uid, long ttlSeconds) {
        return builder(uid, ttlSeconds).signWith(GOOGLE_KEYS.getPrivate(), Jwts.SIG.RS256);
    }

    private JwtBuilder builder(String uid) {
        return builder(uid, 3600);
    }

    /** ID token con la forma de uno real de Firebase (sin firmar todavía). */
    private JwtBuilder builder(String uid, long ttlSeconds) {
        return Jwts.builder()
                .header().keyId("kid-google").and()
                .issuer(ISSUER).audience().add(PROJECT_ID).and()
                .subject(uid)
                .claim("auth_time", Instant.now().minusSeconds(60).getEpochSecond())
                .issuedAt(new Date(System.currentTimeMillis() - 60_000))
                .expiration(inSeconds(ttlSeconds));
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private static Date inSeconds(long seconds) {
        return new Date(System.currentTimeMillis() + seconds * 1000);
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
