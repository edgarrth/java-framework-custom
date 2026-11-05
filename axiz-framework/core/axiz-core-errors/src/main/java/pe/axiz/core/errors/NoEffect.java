package pe.axiz.core.errors;

/** Explicit assurance that the entire rejected operation has not produced any effect. */
public sealed interface NoEffect permits RejectedBeforeEffect {}
