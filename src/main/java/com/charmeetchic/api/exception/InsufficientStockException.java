package com.charmeetchic.api.exception;

/** No hay stock suficiente para atender la cantidad pedida. Se traduce a HTTP 409. */
public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(String productName, int available, int requested) {
        super("Stock insuficiente para '" + productName + "': disponible " + available
                + ", solicitado " + requested);
    }
}
