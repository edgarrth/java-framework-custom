package pe.axiz.payments.beneficiaries.repository;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * Barrera (fence) del directorio del cliente. Aquí no se almacena ningún valor de cuenta ni hash de
 * cuenta sin sal.
 */
@DynamoDbBean
public class DirectoryVersion {
  static final String KEY = "!directory";
  private String customerId = "";
  private String beneficiaryId = KEY;
  private long revision;

  public DirectoryVersion() {}

  static DirectoryVersion next(String customer, long previous) {
    var item = new DirectoryVersion();
    item.customerId = customer;
    item.revision = Math.incrementExact(previous);
    return item;
  }

  @DynamoDbPartitionKey
  public String getCustomerId() {
    return customerId;
  }

  public void setCustomerId(String value) {
    customerId = value;
  }

  @DynamoDbSortKey
  public String getBeneficiaryId() {
    return beneficiaryId;
  }

  public void setBeneficiaryId(String value) {
    beneficiaryId = value;
  }

  public long getRevision() {
    return revision;
  }

  public void setRevision(long value) {
    revision = value;
  }
}
