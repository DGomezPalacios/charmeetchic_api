package com.charmeetchic.kafkaadmin;

import com.charmeetchic.kafkaadmin.dto.AdminRequests.CreateTopicRequest;
import com.charmeetchic.kafkaadmin.dto.AdminResponses.GroupDetail;
import com.charmeetchic.kafkaadmin.dto.AdminResponses.GroupSummary;
import com.charmeetchic.kafkaadmin.dto.AdminResponses.OperationResult;
import com.charmeetchic.kafkaadmin.dto.AdminResponses.PartitionDetail;
import com.charmeetchic.kafkaadmin.dto.AdminResponses.PartitionLag;
import com.charmeetchic.kafkaadmin.dto.AdminResponses.TopicDetail;
import com.charmeetchic.kafkaadmin.dto.AdminResponses.TopicSummary;
import com.charmeetchic.kafkaadmin.exception.AdminExceptions.BadRequestException;
import com.charmeetchic.kafkaadmin.exception.AdminExceptions.BrokerUnavailableException;
import com.charmeetchic.kafkaadmin.exception.AdminExceptions.ConflictException;
import com.charmeetchic.kafkaadmin.exception.AdminExceptions.NotFoundException;
import com.charmeetchic.kafkaadmin.exception.AdminExceptions.ProtectedResourceException;
import com.charmeetchic.kafkaadmin.security.FirebaseTokenValidators;
import com.charmeetchic.kafkaadmin.service.KafkaAdminService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API completa (seguridad + validación + errores) sin Kafka: {@link KafkaAdminService} se simula. Los tokens se
 * firman con una clave RSA de prueba y se validan con las MISMAS reglas que en producción
 * ({@link FirebaseTokenValidators}); solo cambia de dónde sale la clave pública.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KafkaAdminApiIntegrationTest {

    private static final String PROJECT = "charme-et-chic";
    private static final KeyPair KEYS = rsaKeyPair();

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
    private KafkaAdminService service;

    private static final TopicDetail DETAIL = new TopicDetail("demo.prueba", false, false, 3,
            List.of(new PartitionDetail(0, 1, List.of(1, 2, 3), List.of(1, 2, 3), true)),
            Map.of("retention.ms", "60000"));

    // ------------------------------------------------------------------ seguridad

    @Test
    void healthEsPublicoYElRestoExigeToken() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/topics"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
        mvc.perform(auth(get("/topics"), token(c -> c.audience("otro-proyecto")))).andExpect(status().isUnauthorized());
    }

    @Test
    void usuarioSinRolAdminEs403() throws Exception {
        mvc.perform(auth(get("/topics"), token(c -> c.claim("roles", List.of("USER")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("ADMIN")));
        verify(service, never()).listTopics(anyBoolean());
    }

    // ------------------------------------------------------------------ tópicos

    @Test
    void crearListarDescribirModificarYBorrar() throws Exception {
        when(service.listTopics(true)).thenReturn(List.of(new TopicSummary("__consumer_offsets", 50, 3, true, true)));
        when(service.describeTopic("demo.prueba")).thenReturn(DETAIL);
        when(service.createTopic(any(CreateTopicRequest.class))).thenReturn(DETAIL);
        when(service.updateConfigs(eq("demo.prueba"), anyMap())).thenReturn(DETAIL);
        when(service.increasePartitions("demo.prueba", 6)).thenReturn(DETAIL);
        when(service.deleteTopic("demo.prueba")).thenReturn(new OperationResult("demo.prueba", "DELETE", "ok"));

        mvc.perform(auth(get("/topics?internos=true"), admin())).andExpect(jsonPath("$[0].protegido").value(true));
        mvc.perform(auth(get("/topics/demo.prueba"), admin())).andExpect(jsonPath("$.partitions[0].isr.length()").value(3));
        mvc.perform(json(post("/topics"), """
                        {"name":"demo.prueba","partitions":3,"replicationFactor":3,"configs":{"retention.ms":"60000"}}"""))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/topics/demo.prueba")));
        mvc.perform(json(patch("/topics/demo.prueba/configs"), "{\"configs\":{\"cleanup.policy\":\"compact\"}}"))
                .andExpect(status().isOk());
        mvc.perform(json(post("/topics/demo.prueba/partitions"), "{\"totalPartitions\":6}")).andExpect(status().isOk());
        mvc.perform(auth(delete("/topics/demo.prueba"), admin())).andExpect(jsonPath("$.operacion").value("DELETE"));
    }

    @Test
    void validacionDeLosDtos() throws Exception {
        mvc.perform(json(post("/topics"), "{\"name\":\"mal nombre!\",\"partitions\":0,\"replicationFactor\":4}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.name").exists())
                .andExpect(jsonPath("$.details.partitions").exists())
                .andExpect(jsonPath("$.details.replicationFactor").exists());
        mvc.perform(json(post("/topics"), "{\"name\":\"__interno\",\"partitions\":1,\"replicationFactor\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(json(post("/topics"),
                        "{\"name\":\"t\",\"partitions\":1,\"replicationFactor\":1,\"configs\":{\"cleanup.policy\":\"borrar\",\"foo\":\"1\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.configsValid", containsString("cleanup.policy")))
                .andExpect(jsonPath("$.details.configsValid", containsString("foo")));
        mvc.perform(json(patch("/topics/t/configs"), "{\"configs\":{\"retention.ms\":\"siete dias\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.configsValid", containsString("entero")));
        mvc.perform(json(patch("/topics/t/configs"), "{\"configs\":{}}")).andExpect(status().isBadRequest());
        mvc.perform(json(post("/topics/t/partitions"), "{\"totalPartitions\":1}")).andExpect(status().isBadRequest());
        mvc.perform(json(post("/topics"), "no es json")).andExpect(status().isBadRequest());
        mvc.perform(auth(get("/topics/nombre con espacios"), admin())).andExpect(status().isBadRequest());
        mvc.perform(json(post("/consumer-groups/g/reset-offsets"), "{\"topic\":\"t\",\"strategy\":\"OFFSET\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.offsetPresentWhenNeeded").exists());
        verify(service, never()).createTopic(any());
    }

    @Test
    void erroresDelServicioSeTraducenAHttp() throws Exception {
        when(service.describeTopic("no.existe")).thenThrow(new NotFoundException("no existe"));
        when(service.deleteTopic("ventas.eventos")).thenThrow(new ProtectedResourceException("protegido"));
        when(service.increasePartitions(anyString(), anyInt())).thenThrow(new BadRequestException("solo aumentar"));
        when(service.listTopics(false)).thenThrow(new BrokerUnavailableException("caído", null));
        when(service.resetOffsets(eq("activo"), any())).thenThrow(new ConflictException("tiene consumidores"));
        when(service.listGroups()).thenThrow(new IllegalStateException("bug"));

        mvc.perform(auth(get("/topics/no.existe"), admin())).andExpect(status().isNotFound());
        mvc.perform(auth(delete("/topics/ventas.eventos"), admin())).andExpect(status().isForbidden());
        mvc.perform(json(post("/topics/t/partitions"), "{\"totalPartitions\":2}")).andExpect(status().isBadRequest());
        mvc.perform(auth(get("/topics"), admin())).andExpect(status().isServiceUnavailable());
        mvc.perform(json(post("/consumer-groups/activo/reset-offsets"), "{\"topic\":\"t\",\"strategy\":\"EARLIEST\"}"))
                .andExpect(status().isConflict());
        mvc.perform(auth(get("/consumer-groups"), admin())).andExpect(status().isInternalServerError());
    }

    // ------------------------------------------------------------------ consumer groups

    @Test
    void gruposConLag() throws Exception {
        when(service.describeGroup("metricas-ventas")).thenReturn(new GroupDetail("metricas-ventas", "STABLE", 1,
                List.of(), List.of(new PartitionLag("ventas.eventos", 0, 10L, 25L, 15)), 15));
        mvc.perform(auth(get("/consumer-groups/metricas-ventas"), admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lagTotal").value(15))
                .andExpect(jsonPath("$.offsets[0].logEnd").value(25));
        when(service.listGroups()).thenReturn(List.of(new GroupSummary("metricas-ventas", "STABLE")));
        when(service.resetOffsets(eq("g"), any())).thenReturn(new GroupDetail("g", "EMPTY", 1, List.of(), List.of(), 0));
        mvc.perform(json(post("/consumer-groups/g/reset-offsets"), "{\"topic\":\"t\",\"strategy\":\"LATEST\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("EMPTY"));
    }

    // ------------------------------------------------------------------ apoyo

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, String body) {
        return auth(req, admin()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder req, String token) {
        return req.header("Authorization", "Bearer " + token);
    }

    private static String admin() {
        return token(c -> c.claim("ADMIN", true));
    }

    private static String token(Consumer<JWTClaimsSet.Builder> customizer) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer("https://securetoken.google.com/" + PROJECT)
                .audience(PROJECT)
                .subject("uid-admin")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)));
        customizer.accept(builder);
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), builder.build());
            jwt.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate()));
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
