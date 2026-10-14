package pe.axiz.core.sensitive;

/** Masking strategies. */
public enum MaskType {
  /** Keep the value. */
  NONE,
  /** {@code a****@domain.com}. */
  EMAIL,
  /** Keep the last 3 digits. */
  PHONE,
  /** Keep the last 4 characters. */
  DOCUMENT,
  /** PCI: first 6 and last 4 digits at most. */
  CARD,
  /** Keep the first and last character. */
  PARTIAL,
  /** Person names: keep the initial of each word ({@code Ana Torres -> A** T*****}). */
  NAME,
  /** Replace everything with a fixed mask. */
  FULL
}
