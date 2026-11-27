package pe.axiz.core.sensitive;

/** Estrategias de enmascaramiento. */
public enum MaskType {
  /** Conserva el valor. */
  NONE,
  /** {@code a****@domain.com}. */
  EMAIL,
  /** Conserva los últimos 3 dígitos. */
  PHONE,
  /** Conserva los últimos 4 caracteres. */
  DOCUMENT,
  /** PCI: como máximo los primeros 6 y los últimos 4 dígitos. */
  CARD,
  /** Conserva el primer y el último carácter. */
  PARTIAL,
  /**
   * Nombres de personas: conserva la inicial de cada palabra ({@code Ana Torres -> A** T*****}).
   */
  NAME,
  /** Reemplaza todo por una máscara fija. */
  FULL
}
