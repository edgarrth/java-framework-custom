package pe.axiz.spring.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.crypto.AesGcm;
import pe.axiz.core.crypto.FieldEncryptor;
import pe.axiz.core.crypto.PasswordHasher;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.core.secrets.SecretValue;

class CryptoTest {

  @Test
  void configuredEmptySecretIdentifiersFailAtStartup() {
    for (String key :
        new String[] {
          "axiz.crypto.fields.current-key-id", "axiz.crypto.fields.index-current-key-id"
        }) {
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizCryptoAutoConfiguration.class))
          .withPropertyValues(key + "=", "axiz.crypto.password.algorithm=pbkdf2")
          .run(
              ctx ->
                  assertThat(ctx.getStartupFailure()).hasStackTraceContaining("must not be blank"));
    }
  }

  @Test
  void fieldKeysComeFromSecretsAndStayInTheirContext() {
    String k1 = Base64.getEncoder().encodeToString(AesGcm.newKey().getEncoded());
    String k2 = Base64.getEncoder().encodeToString(AesGcm.newKey().getEncoded());
    String hmac = Base64.getEncoder().encodeToString(new byte[32]);
    Map<String, String> store = Map.of("field-key-k1", k1, "field-key-k2", k2, "blind", hmac);
    SecretResolver secrets = name -> Optional.ofNullable(store.get(name)).map(SecretValue::of);
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizCryptoAutoConfiguration.class))
        .withBean(SecretResolver.class, () -> secrets)
        .withPropertyValues(
            "axiz.crypto.fields.current-key-id=k2",
            "axiz.crypto.fields.key-ids=k1,k2",
            "axiz.crypto.fields.index-current-key-id=i1",
            "axiz.crypto.fields.index-secrets.i1=blind",
            "axiz.crypto.password.algorithm=pbkdf2")
        .run(
            ctx -> {
              FieldEncryptor enc = ctx.getBean(FieldEncryptor.class);
              String cipher = enc.encrypt("4111111111111111", "card.pan");
              assertNotEquals("4111111111111111", cipher);
              assertEquals("4111111111111111", enc.decrypt(cipher, "card.pan"));
              assertEquals("4111111111111111", enc.decrypt(cipher, "card.pan"));
              PasswordHasher hasher = ctx.getBean(PasswordHasher.class);
              assertTrue(hasher.matches("pw".toCharArray(), hasher.hash("pw".toCharArray())));
            });
  }
}
