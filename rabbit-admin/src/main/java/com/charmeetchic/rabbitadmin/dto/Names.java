package com.charmeetchic.rabbitadmin.dto;

/** Reglas de nombres compartidas por los DTOs (un solo lugar para las expresiones regulares). */
public final class Names {

    private Names() {
    }

    /**
     * Letras, números, '.', '_', '-' y ':'; debe empezar con letra o número. Ej.: "pedidos.web", "cola_1".
     * Un valor vacío o en blanco pasa este patrón a propósito: lo reporta {@code @NotBlank} con un solo mensaje.
     */
    public static final String NAME_PATTERN = "^(\\s*|[A-Za-z0-9][A-Za-z0-9._:-]*)$";
    public static final String NAME_MESSAGE =
            "solo puede tener letras, números, '.', '_', '-' y ':', y debe empezar con letra o número";

    /** RabbitMQ reserva el prefijo "amq." para sus propios recursos y rechaza declararlos. */
    public static final String NOT_AMQ_PATTERN = "^(?!amq\\.).*$";
    public static final String NOT_AMQ_MESSAGE = "el prefijo 'amq.' está reservado por RabbitMQ";

    /** Routing key: palabras separadas por '.', con comodines '*' y '#' para exchanges topic. Puede ser vacía. */
    public static final String ROUTING_KEY_PATTERN = "^[A-Za-z0-9._:*#-]*$";
    public static final String ROUTING_KEY_MESSAGE =
            "solo puede tener letras, números, '.', '_', '-', ':' y los comodines '*' y '#'";

    /** Límite de RabbitMQ para nombres y routing keys. */
    public static final int MAX_LENGTH = 255;
}
