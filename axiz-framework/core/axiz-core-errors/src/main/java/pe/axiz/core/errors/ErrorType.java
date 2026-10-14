package pe.axiz.core.errors;

/** Nature of an error, independent of its HTTP representation. */
public enum ErrorType {
  /** A business rule rejected the operation (expected outcome). */
  BUSINESS,
  /** The input is malformed or incomplete. */
  VALIDATION,
  /** Authentication or authorization failure. */
  SECURITY,
  /** Unexpected failure inside the service. */
  TECHNICAL,
  /** A dependency (third party, legacy system, other service) failed or rejected the call. */
  EXTERNAL
}
