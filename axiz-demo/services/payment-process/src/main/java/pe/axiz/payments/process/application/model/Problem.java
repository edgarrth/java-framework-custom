package pe.axiz.payments.process.application.model;

import org.jspecify.annotations.Nullable;

/** Application contract independent of HTTP serialization and validation annotations. */
public record Problem(
    @Nullable String type,
    @Nullable String title,
    @Nullable Integer status,
    @Nullable String detail,
    @Nullable String code) {}
