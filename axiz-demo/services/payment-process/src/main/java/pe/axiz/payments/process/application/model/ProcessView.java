package pe.axiz.payments.process.application.model;

import org.jspecify.annotations.Nullable;

/**
 * Contrato de aplicación independiente de la serialización HTTP y de las anotaciones de validación.
 */
public record ProcessView(
    @Nullable String processId, @Nullable String status, @Nullable String failedStep) {}
