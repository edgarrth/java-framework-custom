package pe.axiz.spring.jpa;

import jakarta.persistence.Convert;
import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.orm.jpa.EntityManagerFactoryInfo;
import org.springframework.util.ClassUtils;
import pe.axiz.kit.jpa.EncryptedConverter;

/** Valida los converters reales de las entidades después de que JPA ha construido su metamodelo. */
final class EncryptionContextValidator implements SmartInitializingSingleton {
  private final ApplicationContext context;

  EncryptionContextValidator(ApplicationContext context) {
    this.context = context;
  }

  @Override
  public void afterSingletonsInstantiated() {
    context.getBeanProvider(EncryptedConverter.class).stream()
        .forEach(EncryptedConverter::encryptionContext);
    context.getBeanProvider(EntityManagerFactory.class).stream()
        .forEach(this::validateManagedConverters);
    Map<String, List<String>> shared = new LinkedHashMap<>();
    context.getBeanProvider(EntityManagerFactory.class).stream()
        .forEach(
            factory ->
                factory
                    .getMetamodel()
                    .getEntities()
                    .forEach(
                        entity -> {
                          Class<?> type = entity.getJavaType();
                          for (Class<?> cursor = type;
                              cursor != null && cursor != Object.class;
                              cursor = cursor.getSuperclass()) {
                            inspect(cursor, type.getName(), shared);
                            for (var field : cursor.getDeclaredFields())
                              inspect(field, type.getName() + "." + field.getName(), shared);
                            for (var method : cursor.getDeclaredMethods())
                              inspect(method, type.getName() + "." + method.getName(), shared);
                          }
                        }));
    shared.forEach(
        (aad, fields) -> {
          if (fields.size() > 1)
            LoggerFactory.getLogger(EncryptionContextValidator.class)
                .warn("Encryption context '{}' intentionally shared by {}", aad, fields);
        });
  }

  private void validateManagedConverters(EntityManagerFactory factory) {
    if (!ClassUtils.isPresent(
        "org.springframework.orm.jpa.EntityManagerFactoryInfo", context.getClassLoader())) return;
    ManagedConverters.types(factory)
        .forEach(
            name -> {
              Class<?> type = ClassUtils.resolveClassName(name, context.getClassLoader());
              if (EncryptedConverter.class.isAssignableFrom(type)
                  && !Modifier.isAbstract(type.getModifiers()))
                converter(type.asSubclass(EncryptedConverter.class)).encryptionContext();
            });
  }

  private EncryptedConverter converter(Class<? extends EncryptedConverter> type) {
    EncryptedConverter candidate = context.getBeanProvider(type).getIfAvailable();
    return candidate != null ? candidate : context.getAutowireCapableBeanFactory().createBean(type);
  }

  private static final class ManagedConverters {
    static List<String> types(EntityManagerFactory factory) {
      if (factory instanceof EntityManagerFactoryInfo info) {
        var unit = info.getPersistenceUnitInfo();
        if (unit != null) return unit.getManagedClassNames();
      }
      return List.of();
    }
  }

  private void inspect(AnnotatedElement element, String field, Map<String, List<String>> shared) {
    for (Convert conversion : element.getAnnotationsByType(Convert.class)) {
      if (conversion.disableConversion()
          || !EncryptedConverter.class.isAssignableFrom(conversion.converter())) continue;
      Class<? extends EncryptedConverter> type =
          conversion.converter().asSubclass(EncryptedConverter.class);
      EncryptedConverter bean = converter(type);
      shared.computeIfAbsent(bean.encryptionContext(), key -> new ArrayList<>()).add(field);
    }
  }
}
