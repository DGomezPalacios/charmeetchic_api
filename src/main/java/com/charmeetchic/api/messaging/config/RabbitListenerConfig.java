package com.charmeetchic.api.messaging.config;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuración de los consumidores ({@code @RabbitListener}). La topología está en {@link RabbitMQConfig}.
 */
@Configuration
public class RabbitListenerConfig {

    /**
     * Fábrica de los contenedores de {@code @RabbitListener}. Toma la configuración del yml
     * ({@code spring.rabbitmq.listener.simple.*}: ACK manual, prefetch...) y cambia SOLO el conversor.
     *
     * <p><b>Por qué:</b> con el conversor JSON, Spring convierte el mensaje ANTES de llamar al consumidor. Si el
     * JSON viene malo, la excepción ocurre fuera de nuestro código y, con ACK manual, nadie hace ACK ni NACK:
     * el mensaje queda "Unacked" hasta que se cierra la conexión (lo comprobamos con RabbitMQ real). Con
     * {@link RawBodyMessageConverter} Spring no interpreta nada y {@code ManualAckProcessor} convierte, valida y
     * decide en todos los casos.
     *
     * <p>Se llama {@code rabbitListenerContainerFactory} para reemplazar la que crea Spring Boot por defecto.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(new RawBodyMessageConverter());
        return factory;
    }

    /** Conversor de solo lectura que entrega el cuerpo tal cual (byte[]), sin deserializar. */
    static final class RawBodyMessageConverter implements MessageConverter {

        @Override
        public Message toMessage(Object object, MessageProperties messageProperties) {
            throw new MessageConversionException("Los consumidores no envían mensajes con este conversor");
        }

        @Override
        public Object fromMessage(Message message) {
            return message.getBody();
        }
    }
}
