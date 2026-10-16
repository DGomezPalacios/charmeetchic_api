package com.charmeetchic.api.messaging.producer;

import com.charmeetchic.api.messaging.config.MessagingProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Publicar solo tras el commit (simulando el ciclo de vida de una transacción de Spring). */
class AfterCommitRabbitSenderTest {

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);

    @AfterEach
    void cleanUp() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void dentroDeUnaTransaccion_esperaAlCommit() {
        TransactionSynchronizationManager.initSynchronization();
        sender(true).send("ex", "rk", "hola", "id-1");

        verifyNoInteractions(rabbitTemplate); // todavía no: la transacción no ha terminado

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(rabbitTemplate).convertAndSend(eq("ex"), eq("rk"), eq((Object) "hola"), any(MessagePostProcessor.class));
    }

    @Test
    void siLaTransaccionHaceRollback_nuncaSePublica() {
        TransactionSynchronizationManager.initSynchronization();
        sender(true).send("ex", "rk", "hola", "id-1");

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void sinTransaccion_publicaAlInstante() {
        sender(true).send("ex", "rk", "hola", "id-1");

        verify(rabbitTemplate).convertAndSend(eq("ex"), eq("rk"), eq((Object) "hola"), any(MessagePostProcessor.class));
    }

    @Test
    void mensajeriaDeshabilitada_noPublica() {
        sender(false).send("ex", "rk", "hola", "id-1");

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class),
                any(MessagePostProcessor.class));
    }

    @Test
    void brokerCaido_noRompeLaOperacionDeNegocio() {
        doThrow(new AmqpConnectException(new ConnectException("Connection refused")))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class),
                        any(MessagePostProcessor.class));

        assertThatCode(() -> sender(true).send("ex", "rk", "hola", "id-1")).doesNotThrowAnyException();
    }

    private AfterCommitRabbitSender sender(boolean enabled) {
        return new AfterCommitRabbitSender(rabbitTemplate, new MessagingProperties(enabled,
                new MessagingProperties.Retry(3, 0), new MessagingProperties.Demo(false), null, null));
    }
}
