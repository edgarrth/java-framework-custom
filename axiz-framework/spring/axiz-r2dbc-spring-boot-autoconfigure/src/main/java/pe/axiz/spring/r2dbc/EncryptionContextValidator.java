package pe.axiz.spring.r2dbc;

import java.util.ArrayList;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;
import org.springframework.data.relational.core.mapping.RelationalPersistentProperty;
import pe.axiz.kit.r2dbc.EncryptedText;

/** El converter escalar tiene un único propósito declarado; compartirlo es explícito y visible. */
final class EncryptionContextValidator implements SmartInitializingSingleton {
  private final ApplicationContext context;
  private final @Nullable String purpose;

  EncryptionContextValidator(ApplicationContext context, @Nullable String purpose) {
    this.context = context;
    this.purpose = purpose;
  }

  @Override
  public void afterSingletonsInstantiated() {
    var fields = new ArrayList<String>();
    context.getBeanProvider(R2dbcMappingContext.class).stream()
        .forEach(
            mapping ->
                mapping
                    .getPersistentEntities()
                    .forEach(
                        entity ->
                            entity.doWithProperties(
                                (RelationalPersistentProperty property) -> {
                                  if (property.getType() == EncryptedText.class)
                                    fields.add(
                                        entity.getType().getName() + "." + property.getName());
                                })));
    if (!fields.isEmpty() && purpose == null)
      throw new IllegalStateException(
          "EncryptedText fields require axiz.r2dbc.encryption-context=entity.field or purpose:name:"
              + " "
              + fields);
    if (fields.size() > 1)
      LoggerFactory.getLogger(EncryptionContextValidator.class)
          .warn("Encryption context '{}' intentionally shared by {}", purpose, fields);
  }
}
