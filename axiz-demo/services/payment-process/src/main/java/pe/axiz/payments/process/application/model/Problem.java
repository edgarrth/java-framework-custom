package pe.axiz.payments.process.application.model;

import org.jspecify.annotations.Nullable;

/**
 * Contrato de aplicación independiente de la serialización HTTP y de las anotaciones de validación.
 */
public record Problem(
    @Nullable String type,
    @Nullable String title,
    @Nullable Integer status,
    @Nullable String detail,
    @Nullable String code) {}
