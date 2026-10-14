package pe.axiz.payments.beneficiaries.model;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.sensitive.MaskType;
import pe.axiz.core.sensitive.Sensitive;
import pe.axiz.core.sensitive.SensitivityLevel;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbVersionAttribute;

/**
 * Frequent beneficiary of a customer, stored in the {@code beneficiaries} table (partition key
 * {@code customerId}, sort key {@code beneficiaryId}). Removal is logical: {@code deletedAt} is set
 * and the item stays for audit; queries skip removed items. {@code version} gives optimistic
 * locking on every write.
 */
@DynamoDbBean
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

  /** Required by the DynamoDB enhanced client and JSON codecs. */
  public Beneficiary() {}

  /** A new, active beneficiary. */
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

  /** Changes the alias shown to the customer. */
  public void rename(String newAlias, Instant now) {
    this.alias = newAlias.strip();
    this.updatedAt = now;
  }

  /** Logical removal: the item is kept but no longer listed or returned. */
  public void remove(Instant now) {
    this.deletedAt = now;
    this.updatedAt = now;
  }

  /** Whether the beneficiary was removed (not a bean property, so it is not persisted). */
  public boolean removed() {
    return deletedAt != null;
  }

  /** Whether it points to the same destination account. */
  public boolean sameAccount(String otherCci) {
    return cci.equals(otherCci);
  }

  @DynamoDbPartitionKey
  public String getCustomerId() {
    return customerId;
  }

  public void setCustomerId(String customerId) {
    this.customerId = customerId;
  }

  @DynamoDbSortKey
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

  @DynamoDbVersionAttribute
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
