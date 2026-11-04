package pe.axiz.payments.process.application.model;

import org.jspecify.annotations.Nullable;

/** Application contract independent of HTTP serialization and validation annotations. */
public record ProcessView(
    @Nullable String processId, @Nullable String status, @Nullable String failedStep) {}
