package pe.axiz.spring.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.spi.PersistenceUnitInfo;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.orm.jpa.EntityManagerFactoryInfo;
import pe.axiz.core.crypto.AesGcm;
import pe.axiz.core.crypto.FieldEncryptor;
import pe.axiz.core.crypto.StaticKeyProvider;
import pe.axiz.kit.jpa.EncryptedConverter;

class EncryptionContextSafetyTest {
  public static class LegacyContext extends EncryptedConverter {
    public LegacyContext(FieldEncryptor encryptor) {
      super(encryptor);
    }

    @Override
    protected String context() {
      return "axiz.field";
    }
  }

  @Converter(autoApply = true)
  public static class AutoLegacyContext extends LegacyContext {
    public AutoLegacyContext(FieldEncryptor encryptor) {
      super(encryptor);
    }
  }

  public static class SharedContext extends EncryptedConverter {
    public SharedContext(FieldEncryptor encryptor) {
      super(encryptor);
    }

    @Override
    protected String context() {
      return "purpose:customer-contact";
    }
  }

  static class InvalidEntity {
    @Convert(converter = LegacyContext.class)
    String account;
  }

  static class SharedEntity {
    @Convert(converter = SharedContext.class)
    String first;

    @Convert(converter = SharedContext.class)
    String second;
  }

  private ApplicationContextRunner runner(Class<?> entity, List<String> managedTypes) {
    var factory =
        mock(
            EntityManagerFactory.class,
            withSettings().extraInterfaces(EntityManagerFactoryInfo.class));
    var model = mock(Metamodel.class);
    var type = mock(EntityType.class);
    when(factory.getMetamodel()).thenReturn(model);
    doReturn(entity).when(type).getJavaType();
    when(model.getEntities()).thenReturn(Set.of(type));
    var unit = mock(PersistenceUnitInfo.class);
    when(unit.getManagedClassNames()).thenReturn(managedTypes);
    when(((EntityManagerFactoryInfo) factory).getPersistenceUnitInfo()).thenReturn(unit);
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizJpaAutoConfiguration.class))
        .withBean(EntityManagerFactory.class, () -> factory)
        .withBean(
            FieldEncryptor.class,
            () -> new FieldEncryptor(new StaticKeyProvider("k", Map.of("k", AesGcm.newKey()))));
  }

  @Test
  void explicitLegacyContextFailsAtStartupBeforeAnyWrite() {
    runner(InvalidEntity.class, List.of())
        .run(
            ctx -> {
              assertNotNull(ctx.getStartupFailure());
              assertThat(ctx.getStartupFailure()).hasStackTraceContaining("axiz.field");
            });
  }

  @Test
  void autoApplyLegacyContextCannotBypassTheStartupValidation() {
    runner(SharedEntity.class, List.of(AutoLegacyContext.class.getName()))
        .run(
            ctx -> {
              assertNotNull(ctx.getStartupFailure());
              assertThat(ctx.getStartupFailure()).hasStackTraceContaining("axiz.field");
            });
  }

  @Test
  void intentionalPurposeSharingIsAccepted() {
    runner(SharedEntity.class, List.of(SharedContext.class.getName()))
        .run(ctx -> assertNull(ctx.getStartupFailure()));
  }
}
