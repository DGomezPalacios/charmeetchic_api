package com.charmeetchic.rabbitadmin.exception;

/**
 * Excepciones de negocio del administrador. Las lanza {@code RabbitAdminService} y las traduce a HTTP el
 * {@link GlobalExceptionHandler}; así el servicio no sabe de HTTP y el controlador no sabe de RabbitMQ.
 */
public final class AdminExceptions {

    private AdminExceptions() {
    }

    /** 404: la cola, el exchange o el binding no existe. */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    /** 409: ya existe con otra configuración, o RabbitMQ rechazó la operación (cola con mensajes, etc.). */
    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    /** 403: el recurso pertenece a la API de inventario y no se puede crear ni borrar desde aquí. */
    public static class ProtectedResourceException extends RuntimeException {
        public ProtectedResourceException(String message) {
            super(message);
        }
    }

    /** 503: no se pudo hablar con RabbitMQ (AMQP o API de administración). */
    public static class BrokerUnavailableException extends RuntimeException {
        public BrokerUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
