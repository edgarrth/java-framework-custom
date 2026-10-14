package pe.axiz.core.fileformats;

/** Types of fixed-width fields. */
public enum FieldType {
  /** Text, right padded with spaces. */
  TEXT,
  /** Integer, left padded with zeros. */
  INTEGER,
  /** Decimal with implied decimal point, left padded with zeros. */
  DECIMAL,
  /** Date with a pattern. */
  DATE
}
