package pe.axiz.core.sensitive;

/** How harmful the disclosure of a value would be. */
public enum SensitivityLevel {
  /** Internal but harmless (e.g. a product code). */
  LOW,
  /** Personal data (name, email, phone). */
  MEDIUM,
  /** Identifying or financial data (document, account, partial card). */
  HIGH,
  /** Secrets and full card data (password, PIN, CVV, PAN, tokens). Never shown anywhere. */
  CRITICAL
}
