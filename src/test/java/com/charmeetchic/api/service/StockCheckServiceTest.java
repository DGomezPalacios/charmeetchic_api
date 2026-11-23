package com.charmeetchic.api.service;

import com.charmeetchic.api.dto.StockCheckDTO;
import com.charmeetchic.api.exception.GatewayTimeoutException;
import com.charmeetchic.api.exception.ResourceNotFoundException;
import com.charmeetchic.api.exception.ValidationException;
import com.charmeetchic.api.messaging.config.MessagingProperties;
import com.charmeetchic.api.messaging.event.StockQueryReply;
import com.charmeetchic.api.messaging.producer.StockQueryClient;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Lado que pregunta del RPC: 200 / 404 / 504. */
class StockCheckServiceTest {

    private final StockQueryClient client = mock(StockQueryClient.class);
    private final StockCheckService service = new StockCheckService(client, new MessagingProperties(true,
            new MessagingProperties.Retry(3, 0), new MessagingProperties.Rpc(3000), new MessagingProperties.Demo(false),
            null, null, null));

    @Test
    void respuestaEncontrada_devuelveElStock() {
        when(client.query("ACC-EST-003")).thenReturn(Optional.of(new StockQueryReply("ACC-EST-003", true, 15L,
                "Estuche", 4, true, 5, Instant.now(), "consumidor-consulta-stock-1")));

        StockCheckDTO dto = service.check("ACC-EST-003");

        assertThat(dto.stock()).isEqualTo(4);
        assertThat(dto.lowStock()).isTrue();
        assertThat(dto.respondedBy()).isEqualTo("consumidor-consulta-stock-1");
        assertThat(dto.roundTripMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void sinRespuesta_es504() {
        when(client.query("ACC-EST-003")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.check("ACC-EST-003"))
                .isInstanceOf(GatewayTimeoutException.class)
                .hasMessageContaining("3000 ms");
    }

    @Test
    void skuInexistente_es404() {
        when(client.query("NO-EXISTE")).thenReturn(Optional.of(StockQueryReply.notFound("NO-EXISTE", "h")));

        assertThatThrownBy(() -> service.check("NO-EXISTE")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void skuDemasiadoLargo_es400() {
        assertThatThrownBy(() -> service.check("X".repeat(51))).isInstanceOf(ValidationException.class);
    }
}
