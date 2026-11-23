package pe.axiz.spring.dynamodb;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import pe.axiz.core.crypto.StaticKeyProvider;
import pe.axiz.core.secrets.SecretNotFoundException;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.kit.dynamodb.CursorCodec;
import pe.axiz.spring.foundation.ConditionalOnConfiguredProperty;

/**
 * Carga el material de claves del cursor en el arranque; el procesamiento de solicitudes no realiza
 * E/S contra el almacén de secretos.
 */
@AutoConfiguration(afterName = "pe.axiz.spring.secrets.AxizSecretsAutoConfiguration")
@ConditionalOnClass(CursorCodec.class)
@ConditionalOnConfiguredProperty("axiz.dynamodb.cursor.current-key-id")
@EnableConfigurationProperties(AxizDynamoCursorProperties.class)
public class AxizDynamoCursorAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  CursorCodec axizDynamoCursorCodec(AxizDynamoCursorProperties properties, SecretResolver secrets) {
    String current = Objects.requireNonNull(properties.currentKeyId());
    Map<String, String> keys = new LinkedHashMap<>();
    var ids = new LinkedHashSet<>(properties.keyIds());
    ids.add(current);
    for (String id : ids) {
      if (!id.matches("[A-Za-z0-9_-]{1,32}"))
        throw new IllegalArgumentException("Invalid cursor key id");
      String name = properties.secretPrefix() + id;
      keys.put(
          id, secrets.resolve(name).orElseThrow(() -> new SecretNotFoundException(name)).reveal());
    }
    return new CursorCodec(StaticKeyProvider.fromBase64(current, keys), properties.encrypt());
  }
}
