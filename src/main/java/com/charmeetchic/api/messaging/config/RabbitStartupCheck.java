package com.charmeetchic.api.messaging.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Al terminar de arrancar, abre una conexión de prueba con RabbitMQ.
 *
 * <p>Abrir la conexión dispara al {@code RabbitAdmin}, que declara en el broker todas las colas, exchanges y
 * bindings de {@link RabbitMQConfig}. Sin esto, la topología recién se crearía con el primer mensaje.
 *
 * <p>Si RabbitMQ no responde, solo se deja un WARN: la API REST sigue funcionando (el inventario no debe
 * caerse porque la mensajería esté abajo) y Spring AMQP reintenta la conexión en el siguiente uso.
 *
 * <p>Se desactiva con {@code app.messaging.check-on-startup=false} (lo hacen los tests).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.messaging", name = "check-on-startup", havingValue = "true", matchIfMissing = true)
public class RabbitStartupCheck implements ApplicationRunner {

    private final ConnectionFactory connectionFactory;

    public RabbitStartupCheck(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection ignored = connectionFactory.createConnection()) {
            log.info("Conectado a RabbitMQ en {}:{} (vhost '{}'); topología declarada",
                    connectionFactory.getHost(), connectionFactory.getPort(), connectionFactory.getVirtualHost());
        } catch (AmqpException e) {
            log.warn("RabbitMQ no disponible al arrancar ({}). La API sigue funcionando; "
                    + "la mensajería se reconectará en el próximo uso.", e.getMessage());
        }
    }
}
