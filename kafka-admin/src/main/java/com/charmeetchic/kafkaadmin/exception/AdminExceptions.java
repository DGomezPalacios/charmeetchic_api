package com.charmeetchic.kafkaadmin.exception;

/**
 * Excepciones de negocio del administrador. Las lanza {@code KafkaAdminService} y las traduce a HTTP el
 * {@link GlobalExceptionHandler}; así el servicio no sabe de HTTP y el controlador no sabe de Kafka.
 */
public final class AdminExceptions {

    private AdminExceptions() {
    }

    /** 400: Kafka rechazó los parámetros (config inválida, menos particiones que las actuales, RF > brokers...). */
    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String message) {
            super(message);
        }
    }

    /** 404: el tópico o el consumer group no existe. */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    /** 409: el tópico ya existe, o el grupo tiene consumidores activos y no se pueden mover sus offsets. */
    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    /** 403: tópico del sistema (de la API de inventario o interno de Kafka) protegido contra esta operación. */
    public static class ProtectedResourceException extends RuntimeException {
        public ProtectedResourceException(String message) {
            super(message);
        }
    }

    /** 503: el clúster no respondió a tiempo. */
    public static class BrokerUnavailableException extends RuntimeException {
        public BrokerUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
