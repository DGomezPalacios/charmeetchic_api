package com.charmeetchic.api.enums;

/** Motivo de un movimiento de stock (auditoría de inventario). */
public enum MovementType {
    /** Stock inicial al crear un producto (entrada). */
    INGRESO_INICIAL,
    /** Venta: se crea una orden (salida). */
    VENTA,
    /** Se cancela una orden PENDING y se devuelve el stock (entrada). */
    CANCELACION_ORDEN,
    /** Un ADMIN cambia el stock a mano al editar el producto (entrada o salida). */
    AJUSTE_MANUAL
}
