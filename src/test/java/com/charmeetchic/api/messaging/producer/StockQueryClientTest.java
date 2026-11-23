package com.charmeetchic.api.messaging.producer;

import com.charmeetchic.api.exception.MessagingUnavailableException;
import com.charmeetchic.api.messaging.config.MessagingProperties;
import com.charmeetchic.api.messaging.event.StockQueryReply;
import com.charmeetchic.api.messaging.event.StockQueryRequest;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.ParameterizedTypeReference;

import java.net.ConnectException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Cliente RPC: respuesta, timeout (null) y RabbitMQ caído. */
class StockQueryClientTest {

    private final RabbitTemplate template = mock(RabbitTemplate.class);

    @Test
    void devuelveLaRespuestaDelConsumidor() {
        StockQueryReply reply = new StockQueryReply("ANI-SOL-001", true, 7L, "Anillo", 15, false, 5, Instant.now(), "h");
        when(template.convertSendAndReceiveAsType(eq("charmeetchic.inventario"), eq("inventario.consulta-stock"),
                eq(new StockQueryRequest("ANI-SOL-001")), any(MessagePostProcessor.class),
                any(ParameterizedTypeReference.class))).thenReturn(reply);

        assertThat(client(true).query("ANI-SOL-001")).contains(reply);
    }

    @Test
    void sinRespuestaDentroDelTimeout_devuelveVacio() {
        when(template.convertSendAndReceiveAsType(any(String.class), any(String.class), any(Object.class),
                any(MessagePostProcessor.class), any(ParameterizedTypeReference.class))).thenReturn(null);

        assertThat(client(true).query("ANI-SOL-001")).isEmpty();
    }

    @Test
    void rabbitCaido_esMessagingUnavailable() {
        when(template.convertSendAndReceiveAsType(any(String.class), any(String.class), any(Object.class),
                any(MessagePostProcessor.class), any(ParameterizedTypeReference.class)))
                .thenThrow(new AmqpConnectException(new ConnectException("Connection refused")));

        assertThatThrownBy(() -> client(true).query("X")).isInstanceOf(MessagingUnavailableException.class);
    }

    @Test
    void mensajeriaDeshabilitada_noIntentaPublicar() {
        assertThatThrownBy(() -> client(false).query("X")).isInstanceOf(MessagingUnavailableException.class);
        verifyNoInteractions(template);
    }

    private StockQueryClient client(boolean enabled) {
        MessagingProperties props = new MessagingProperties(enabled, new MessagingProperties.Retry(3, 0),
                new MessagingProperties.Rpc(3000), new MessagingProperties.Demo(false), null,
                new MessagingProperties.Exchanges("charmeetchic.inventario", "n", "v", "dlx"),
                new MessagingProperties.Queues(null, null, null,
                        new MessagingProperties.RpcQueueProperties("inventario.consulta-stock",
                                "inventario.consulta-stock", 1000), null, null));
        return new StockQueryClient(template, props);
    }
}
