package com.charmeetchic.api.messaging.consumer;

import com.charmeetchic.api.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsumerControlServiceTest {

    private final RabbitListenerEndpointRegistry registry = mock(RabbitListenerEndpointRegistry.class);
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);
    private final ConsumerControlService service = new ConsumerControlService(registry);

    @Test
    void pausarYReanudar_detieneYArrancaElContenedor() {
        when(registry.getListenerContainer("consumidor-movimientos")).thenReturn(container);

        service.pause("consumidor-movimientos");
        verify(container).stop();

        service.resume("consumidor-movimientos");
        verify(container).start();
    }

    @Test
    void idInexistente_es404ConLaListaDeIds() {
        when(registry.getListenerContainerIds()).thenReturn(Set.of("consumidor-movimientos"));

        assertThatThrownBy(() -> service.pause("otro"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("consumidor-movimientos");
    }
}
