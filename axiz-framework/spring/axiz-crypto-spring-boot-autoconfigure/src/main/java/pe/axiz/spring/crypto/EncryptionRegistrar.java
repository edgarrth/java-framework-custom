package pe.axiz.spring.crypto;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import pe.axiz.core.crypto.BlindIndex;
import pe.axiz.core.crypto.EncryptionRegistry;
import pe.axiz.core.crypto.FieldEncryptor;

/**
 * Publishes the field encryptor to {@link EncryptionRegistry} for converters that JPA or DynamoDB
 * instantiate outside the container.
 */
public class EncryptionRegistrar implements InitializingBean, DisposableBean {

  private final FieldEncryptor encryptor;
  private final @Nullable BlindIndex index;

  public EncryptionRegistrar(FieldEncryptor encryptor, @Nullable BlindIndex index) {
    this.encryptor = encryptor;
    this.index = index;
  }

  @Override
  public void afterPropertiesSet() {
    EncryptionRegistry.register(encryptor, index);
  }

  @Override
  public void destroy() {
    EncryptionRegistry.reset();
  }
}
