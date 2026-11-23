package pe.axiz.core.fileformats;

/** Tipos de campos de ancho fijo. */
public enum FieldType {
  /** Texto, rellenado con espacios a la derecha. */
  TEXT,
  /** Entero, rellenado con ceros a la izquierda. */
  INTEGER,
  /** Decimal con punto decimal implícito, rellenado con ceros a la izquierda. */
  DECIMAL,
  /** Fecha con un patrón. */
  DATE
}
