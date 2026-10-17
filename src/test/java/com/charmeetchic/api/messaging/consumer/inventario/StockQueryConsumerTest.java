package com.charmeetchic.api.messaging.consumer.inventario;

import com.charmeetchic.api.messaging.config.MessagingProperties;
import com.charmeetchic.api.messaging.consumer.ManualAckProcessor;
import com.charmeetchic.api.messaging.event.StockQueryReply;
import com.charmeetchic.api.service.StockLookupService;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rabbitmq.client.Channel;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Consumidor RPC: responde a reply_to con el mismo correlation_id y recién entonces hace ACK. */
class StockQueryConsumerTest {

    private static final long TAG = 9L;
    private static final String REPLY_TO = "amq.rabbitmq.reply-to.g1h2AA";

    private final Channel channel = mock(Channel.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final StockLookupService lookup = mock(StockLookupService.class);
    private final StockQueryConsumer consumer = new StockQueryConsumer(new ManualAckProcessor(
            JsonMapper.builder().findAndAddModules().build(), Validation.buildDefaultValidatorFactory().getValidator(),
            rabbitTemplate, new MessagingProperties(true, new MessagingProperties.Retry(3, 0),
            new MessagingProperties.Rpc(3000), new MessagingProperties.Demo(false), null, null, null)),
            lookup, rabbitTemplate);

    @Test
    void respondeAReplyToConElCorrelationIdYHaceAck() throws Exception {
        StockQueryReply reply = new StockQueryReply("COL-PER-001", true, 1L, "Collar", 18, false, 5, Instant.now(), "hilo");
        when(lookup.lookup(eq("COL-PER-001"), anyString())).thenReturn(reply);

        consumer.onStockQuery(request("{\"sku\":\"COL-PER-001\"}", REPLY_TO, "corr-1"), channel);

        ArgumentCaptor<MessagePostProcessor> postProcessor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbitTemplate).convertAndSend(eq(""), eq(REPLY_TO), eq((Object) reply), postProcessor.capture());
        Message response = postProcessor.getValue().postProcessMessage(new Message(new byte[0], new MessageProperties()));
        assertThat(response.getMessageProperties().getCorrelationId()).isEqualTo("corr-1");
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void consultaSinReplyTo_esInvalidaYNoSeResponde() throws Exception {
        consumer.onStockQuery(request("{\"sku\":\"COL-PER-001\"}", null, "corr-2"), channel);

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class),
                any(MessagePostProcessor.class));
        verify(channel).basicNack(TAG, false, false);
    }

    @Test
    void consultaSinSku_esInvalida() throws Exception {
        consumer.onStockQuery(request("{\"sku\":\"\"}", REPLY_TO, "corr-3"), channel);

        verify(channel).basicNack(TAG, false, false);
        verify(lookup, never()).lookup(anyString(), anyString());
    }

    private static Message request(String body, String replyTo, String correlationId) {
        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(TAG);
        props.setConsumerQueue("inventario.consulta-stock");
        props.setMessageId("q-1");
        props.setReplyTo(replyTo);
        props.setCorrelationId(correlationId);
        return new Message(body.getBytes(StandardCharsets.UTF_8), props);
    }
}
