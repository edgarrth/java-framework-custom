package pe.axiz.payments.beneficiaries.model;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.sensitive.MaskType;
import pe.axiz.core.sensitive.Sensitive;
import pe.axiz.core.sensitive.SensitivityLevel;

/**
 * Beneficiario frecuente de un cliente, independiente de su mapeo de persistencia. La eliminación
 * es lógica: se asigna {@code deletedAt} y el elemento se conserva para auditoría; las consultas
 * omiten los elementos eliminados. {@code version} aporta bloqueo optimista en cada escritura.
 */
public class Beneficiary {

  private String customerId = "";
  private String beneficiaryId = "";
  private String alias = "";
  private String holderName = "";
  private String cci = "";
  private String currency = "";
  private @Nullable Instant createdAt;
  private @Nullable Instant updatedAt;
  private @Nullable Instant deletedAt;
  private @Nullable Long version;

  /** Requerido por los codecs JSON y el mapeo del repositorio. */
  public Beneficiary() {}

  /** Beneficiario nuevo y activo. */
  public static Beneficiary register(
      String customerId, String beneficiaryId, NewBeneficiary data, Instant now) {
    Beneficiary b = new Beneficiary();
    b.customerId = customerId;
    b.beneficiaryId = beneficiaryId;
    b.alias = data.alias().strip();
    b.holderName = data.holderName().strip();
    b.cci = data.cci();
    b.currency = data.currency();
    b.createdAt = now;
    b.updatedAt = now;
    return b;
  }

  /** Cambia el alias que se muestra al cliente. */
  public void rename(String newAlias, Instant now) {
    this.alias = newAlias.strip();
    this.updatedAt = now;
  }

  /** Eliminación lógica: el elemento se conserva, pero deja de listarse y de devolverse. */
  public void remove(Instant now) {
    this.deletedAt = now;
    this.updatedAt = now;
  }

  /**
   * Indica si el beneficiario fue eliminado (no es una propiedad de bean, por lo que no se
   * persiste).
   */
  public boolean removed() {
    return deletedAt != null;
  }

  /** Indica si apunta a la misma cuenta de destino. */
  public boolean sameAccount(String otherCci) {
    return cci.equals(otherCci);
  }

  public String getCustomerId() {
    return customerId;
  }

  public void setCustomerId(String customerId) {
    this.customerId = customerId;
  }

  public String getBeneficiaryId() {
    return beneficiaryId;
  }

  public void setBeneficiaryId(String beneficiaryId) {
    this.beneficiaryId = beneficiaryId;
  }

  public String getAlias() {
    return alias;
  }

  public void setAlias(String alias) {
    this.alias = alias;
  }

  @Sensitive(level = SensitivityLevel.MEDIUM, mask = MaskType.PARTIAL)
  public String getHolderName() {
    return holderName;
  }

  public void setHolderName(String holderName) {
    this.holderName = holderName;
  }

  @Sensitive(level = SensitivityLevel.MEDIUM, mask = MaskType.PARTIAL)
  public String getCci() {
    return cci;
  }

  public void setCci(String cci) {
    this.cci = cci;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public @Nullable Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(@Nullable Instant createdAt) {
    this.createdAt = createdAt;
  }

  public @Nullable Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(@Nullable Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  public @Nullable Instant getDeletedAt() {
    return deletedAt;
  }

  public void setDeletedAt(@Nullable Instant deletedAt) {
    this.deletedAt = deletedAt;
  }

  public @Nullable Long getVersion() {
    return version;
  }

  public void setVersion(@Nullable Long version) {
    this.version = version;
  }

  @Override
  public String toString() {
    return "Beneficiary[" + customerId + "/" + beneficiaryId + "]";
  }
}
