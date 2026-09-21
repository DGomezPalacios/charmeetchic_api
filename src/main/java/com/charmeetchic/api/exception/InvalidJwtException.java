package com.charmeetchic.api.exception;

/**
 * El token JWT es inválido: mal formado, firma incorrecta, expirado, emisor/audiencia
 * no esperados, o no se envió credencial donde era obligatoria. Se traduce a HTTP 401.
 */
public class InvalidJwtException extends RuntimeException {

    public InvalidJwtException(String message) {
        super(message);
    }

    public InvalidJwtException(String message, Throwable cause) {
        super(message, cause);
    }
}
