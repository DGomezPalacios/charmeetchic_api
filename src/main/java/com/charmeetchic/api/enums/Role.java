package com.charmeetchic.api.enums;

/**
 * Roles de la aplicación. Se guardan como <i>custom claims</i> de Firebase Authentication
 * ({@code roles: ["ADMIN"]}, {@code role: "ADMIN"} o {@code admin: true}) y viajan en el ID token.
 * Spring Security los expone como authorities {@code ROLE_ADMIN} / {@code ROLE_USER}.
 */
public enum Role {
    /** Gestión de catálogo, categorías, estados de órdenes y reportes. */
    ADMIN,
    /** Cliente autenticado: puede crear y consultar sus propias órdenes. */
    USER
}
