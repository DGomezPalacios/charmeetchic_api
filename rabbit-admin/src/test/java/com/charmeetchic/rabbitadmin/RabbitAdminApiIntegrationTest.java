package com.charmeetchic.rabbitadmin;

import com.charmeetchic.rabbitadmin.dto.BindingRequest;
import com.charmeetchic.rabbitadmin.dto.ExchangeRequest;
import com.charmeetchic.rabbitadmin.dto.QueueRequest;
import com.charmeetchic.rabbitadmin.dto.ResourceInfo.Declared;
import com.charmeetchic.rabbitadmin.dto.ResourceInfo.ExchangeInfo;
import com.charmeetchic.rabbitadmin.dto.ResourceInfo.QueueInfo;
import com.charmeetchic.rabbitadmin.exception.AdminExceptions.ConflictException;
import com.charmeetchic.rabbitadmin.exception.AdminExceptions.NotFoundException;
import com.charmeetchic.rabbitadmin.exception.AdminExceptions.ProtectedResourceException;
import com.charmeetchic.rabbitadmin.security.FirebaseTokenValidators;
import com.charmeetchic.rabbitadmin.dto.AlertInfo;
import com.charmeetchic.rabbitadmin.service.AlertService;
import com.charmeetchic.rabbitadmin.service.RabbitAdminService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.ConnectException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API completa (seguridad + validación + manejo de errores) con el contexto real de Spring y sin RabbitMQ:
 * {@link RabbitAdminService} se simula. Los tokens se firman de verdad con una clave RSA de prueba y se
 * validan con las MISMAS reglas que en producción ({@link FirebaseTokenValidators}); solo cambia de dónde
 * sale la clave pública (aquí, en memoria; en producción, el JWKS de Google).
 */
@SpringBootTest(properties = "app.alerts.enabled=false")
@AutoConfigureMockMvc
class RabbitAdminApiIntegrationTest {

    private static final String PROJECT = "charme-et-chic";
    private static final KeyPair KEYS = rsaKeyPair();
    private static final KeyPair OTHER_KEYS = rsaKeyPair();

    @TestConfiguration
    static class TestJwtConfig {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
            decoder.setJwtValidator(FirebaseTokenValidators.forProject(PROJECT));
            return decoder;
        }
    }

    @Autowired
    private MockMvc mvc;

    @MockBean
    private RabbitAdminService service;

    @MockBean
    private AlertService alertService;

    // ------------------------------------------------------------------ autenticación (401) y autorización (403)

    @Test
    void health_esPublico() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void sinToken_es401ConJson() throws Exception {
        mvc.perform(get("/queues"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void tokenDeOtroProyecto_es401() throws Exception {
        mvc.perform(auth(get("/queues"), token(c -> c.audience("otro-proyecto"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", containsString("aud")));
    }

    @Test
    void tokenConOtroIssuer_es401() throws Exception {
        mvc.perform(auth(get("/queues"), token(c -> c.issuer("https://securetoken.google.com/otro-proyecto"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenVencido_es401() throws Exception {
        // emitido hace 2 h, venció hace 1 h (los ID tokens de Firebase duran 1 h)
        mvc.perform(auth(get("/queues"), token(c -> c.issueTime(Date.from(Instant.now().minusSeconds(7200)))
                        .expirationTime(Date.from(Instant.now().minusSeconds(3600))))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", containsString("expired")));
    }

    @Test
    void tokenFirmadoConOtraClave_es401() throws Exception {
        mvc.perform(auth(get("/queues"), sign(claims(c -> c.claim("ADMIN", true)), (RSAPrivateKey) OTHER_KEYS.getPrivate())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void usuarioSinRolAdmin_es403() throws Exception {
        mvc.perform(auth(get("/queues"), token(c -> c.claim("roles", List.of("USER")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Se requiere el rol ADMIN para administrar RabbitMQ"));
        verify(service, never()).listQueues();
    }

    @Test
    void adminConClaimDeAddAdminJs_tieneAcceso() throws Exception {
        when(service.listQueues()).thenReturn(List.of(new QueueInfo("inventario.movimientos", true, false,
                Map.of(), 0, 1, true)));

        mvc.perform(auth(get("/queues"), adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("inventario.movimientos"))
                .andExpect(jsonPath("$[0].system").value(true));
    }

    @Test
    void lasCuatroFormasDelClaimAdmin_funcionan() throws Exception {
        for (Consumer<JWTClaimsSet.Builder> claim : List.<Consumer<JWTClaimsSet.Builder>>of(
                c -> c.claim("ADMIN", true), c -> c.claim("admin", true),
                c -> c.claim("role", "admin"), c -> c.claim("roles", List.of("USER", "ADMIN")))) {
            mvc.perform(auth(get("/exchanges"), token(claim))).andExpect(status().isOk());
        }
        mvc.perform(auth(get("/exchanges"), token(c -> c.claim("ADMIN", "true")))) // string, no booleano
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ validación (400)

    @Test
    void crearColaSinNombre_es400ConDetalle() throws Exception {
        mvc.perform(auth(post("/queues"), adminToken()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.name").value("el nombre de la cola es obligatorio"));
        verify(service, never()).createQueue(any());
    }

    @Test
    void crearExchangeDeTipoInvalido_es400() throws Exception {
        mvc.perform(auth(post("/exchanges"), adminToken()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"pedidos\",\"type\":\"x-delayed\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.type").value("type debe ser direct, topic, fanout o headers"));
    }

    @Test
    void cuerpoQueNoEsJson_es400() throws Exception {
        mvc.perform(auth(post("/queues"), adminToken()).contentType(MediaType.APPLICATION_JSON).content("{no-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El cuerpo de la solicitud falta o no es un JSON válido"));
    }

    @Test
    void borrarBindingSinParametros_es400() throws Exception {
        mvc.perform(auth(delete("/bindings"), adminToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.exchange").value("el exchange es obligatorio"))
                .andExpect(jsonPath("$.details.queue").value("la cola es obligatoria"));
    }

    // ------------------------------------------------------------------ códigos de resultado

    @Test
    void crearColaNueva_es201_yExistenteIgual_es200() throws Exception {
        QueueInfo info = new QueueInfo("pedidos.web", true, false, Map.of(), 0, 0, false);
        when(service.createQueue(any(QueueRequest.class)))
                .thenReturn(new Declared<>(info, true))
                .thenReturn(new Declared<>(info, false));
        String body = "{\"name\":\"pedidos.web\"}";

        mvc.perform(auth(post("/queues"), adminToken()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("pedidos.web"));
        mvc.perform(auth(post("/queues"), adminToken()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void crearExchange_pasaElDtoNormalizadoAlServicio() throws Exception {
        when(service.createExchange(any())).thenReturn(new Declared<>(
                new ExchangeInfo("pedidos", "topic", true, false, false), true));

        mvc.perform(auth(post("/exchanges"), adminToken()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"pedidos\",\"type\":\"TOPIC\"}"))
                .andExpect(status().isCreated());
        verify(service).createExchange(new ExchangeRequest("pedidos", "topic", true, false));
    }

    @Test
    void conflicto_es409() throws Exception {
        when(service.createQueue(any())).thenThrow(new ConflictException("La cola 'x' ya existe con otra configuración"));

        mvc.perform(auth(post("/queues"), adminToken()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("La cola 'x' ya existe con otra configuración"));
    }

    @Test
    void borrarInexistente_es404_borrarDelSistema_es403_borrarOk_es204() throws Exception {
        doThrow(new NotFoundException("La cola 'fantasma' no existe")).when(service).deleteQueue("fantasma", false, false);
        doThrow(new ProtectedResourceException("pertenece a la API de inventario"))
                .when(service).deleteQueue("inventario.movimientos", false, false);

        mvc.perform(auth(delete("/queues/fantasma"), adminToken())).andExpect(status().isNotFound());
        mvc.perform(auth(delete("/queues/inventario.movimientos"), adminToken())).andExpect(status().isForbidden());
        mvc.perform(auth(delete("/queues/pedidos.web?ifEmpty=true"), adminToken())).andExpect(status().isNoContent());
        verify(service).deleteQueue("pedidos.web", false, true);
    }

    @Test
    void borrarBindingPorParametros_es204() throws Exception {
        mvc.perform(auth(delete("/bindings?exchange=pedidos&queue=copia&routingKey=a.b"), adminToken()))
                .andExpect(status().isNoContent());
        verify(service).deleteBinding(eq(new BindingRequest("pedidos", "copia", "a.b")));
    }

    @Test
    void brokerCaido_es503() throws Exception {
        when(service.listExchanges()).thenThrow(new AmqpConnectException(new ConnectException("Connection refused")));

        mvc.perform(auth(get("/exchanges"), adminToken()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message", containsString("RabbitMQ no está disponible")));
    }

    @Test
    void alertas_soloAdmin_yDevuelveElSnapshot() throws Exception {
        when(alertService.current()).thenReturn(new AlertInfo.Snapshot(Instant.now(), false, 1, List.of(
                new AlertInfo.Alert(AlertInfo.Severity.CRITICA, AlertInfo.Type.DLQ_CON_MENSAJES,
                        "inventario.movimientos.dlq", "1 mensaje(s) fallido(s) esperando revisión", Instant.now()))));

        mvc.perform(auth(get("/alerts"), token(c -> c.claim("roles", List.of("USER")))))
                .andExpect(status().isForbidden());
        mvc.perform(auth(get("/alerts"), adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.alerts[0].type").value("DLQ_CON_MENSAJES"));
        mvc.perform(auth(get("/alerts?refresh=true"), adminToken())).andExpect(status().isOk());
        verify(alertService).refresh();
    }

    // ------------------------------------------------------------------ helpers

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    /** Lo que trae un administrador real: custom claim {"ADMIN": true} asignado por addAdmin.js. */
    private static String adminToken() {
        return token(c -> c.claim("ADMIN", true));
    }

    private static String token(Consumer<JWTClaimsSet.Builder> customizer) {
        return sign(claims(customizer), (RSAPrivateKey) KEYS.getPrivate());
    }

    /** Claims con la forma de un ID token de Firebase válido; el customizer los modifica para cada caso. */
    private static JWTClaimsSet claims(Consumer<JWTClaimsSet.Builder> customizer) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer("https://securetoken.google.com/" + PROJECT)
                .audience(PROJECT)
                .subject("uid-admin")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .claim("email", "admin@charme.cl");
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
