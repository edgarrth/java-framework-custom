package pe.axiz.spring.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;
import pe.axiz.kit.r2dbc.EncryptedText;
import pe.axiz.spring.foundation.aws.AxizAwsIdentityAutoConfiguration;

class EncryptionContextSafetyTest {
  record SecretRow(String id, EncryptedText creditor, EncryptedText debtor) {}

  private ApplicationContextRunner runner() {
    var mapping = new R2dbcMappingContext();
    mapping.getRequiredPersistentEntity(SecretRow.class);
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
        .withConfiguration(AutoConfigurations.of(AxizR2dbcAutoConfiguration.class))
        .withBean(R2dbcMappingContext.class, () -> mapping);
  }

  @Test
  void encryptedFieldsRequireAPurposeAtStartup() {
    runner()
        .run(
            ctx -> {
              assertNotNull(ctx.getStartupFailure());
              assertThat(ctx.getStartupFailure())
                  .hasStackTraceContaining("axiz.r2dbc.encryption-context");
            });
    runner()
        .withPropertyValues("axiz.r2dbc.encryption-context=axiz.field")
        .run(ctx -> assertNotNull(ctx.getStartupFailure()));
  }

  @Test
  void declaredPurposeMayBeIntentionallyShared() {
    runner()
        .withPropertyValues("axiz.r2dbc.encryption-context=purpose:payment-parties")
        .run(ctx -> assertNull(ctx.getStartupFailure()));
  }
}
