package pe.axiz.payments.beneficiaries.repository;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.crypto.EncryptedValue;
import pe.axiz.kit.dynamodb.EncryptedValueAttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.extensions.annotations.DynamoDbVersionAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/** Representación exclusiva de persistencia: la cuenta es un texto cifrado autenticado. */
@DynamoDbBean
public class BeneficiaryItem {
  private String customerId = "";
  private String beneficiaryId = "";
  private String alias = "";
  private String holderName = "";
  private @Nullable EncryptedValue cci;
  private String currency = "";
  private @Nullable Instant createdAt;
  private @Nullable Instant updatedAt;
  private @Nullable Instant deletedAt;
  private @Nullable Long version;

  public BeneficiaryItem() {}

  @DynamoDbPartitionKey
  public String getCustomerId() {
    return customerId;
  }

  public void setCustomerId(String value) {
    this.customerId = value;
  }

  @DynamoDbSortKey
  public String getBeneficiaryId() {
    return beneficiaryId;
  }

  public void setBeneficiaryId(String value) {
    this.beneficiaryId = value;
  }

  public String getAlias() {
    return alias;
  }

  public void setAlias(String value) {
    this.alias = value;
  }

  public String getHolderName() {
    return holderName;
  }

  public void setHolderName(String value) {
    this.holderName = value;
  }

  @DynamoDbConvertedBy(EncryptedValueAttributeConverter.class)
  public @Nullable EncryptedValue getCci() {
    return cci;
  }

  public void setCci(@Nullable EncryptedValue value) {
    this.cci = value;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String value) {
    this.currency = value;
  }

  public @Nullable Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(@Nullable Instant value) {
    this.createdAt = value;
  }

  public @Nullable Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(@Nullable Instant value) {
    this.updatedAt = value;
  }

  public @Nullable Instant getDeletedAt() {
    return deletedAt;
  }

  public void setDeletedAt(@Nullable Instant value) {
    this.deletedAt = value;
  }

  @DynamoDbVersionAttribute
  public @Nullable Long getVersion() {
    return version;
  }

  public void setVersion(@Nullable Long value) {
    this.version = value;
  }

  @Override
  public String toString() {
    return "BeneficiaryItem[redacted]";
  }
}
