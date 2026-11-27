package pe.axiz.core.errors;

/** Garantía explícita de que la operación rechazada completa no produjo ningún efecto. */
public sealed interface NoEffect permits RejectedBeforeEffect {}
