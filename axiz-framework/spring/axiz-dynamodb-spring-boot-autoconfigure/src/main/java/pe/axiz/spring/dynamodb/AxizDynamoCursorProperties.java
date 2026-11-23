package pe.axiz.spring.dynamodb;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Claves de cursor compartidas tomadas de secretos; los ids anteriores se conservan hasta que los
 * clientes puedan reiniciar la paginación.
 */
@ConfigurationProperties("axiz.dynamodb.cursor")
public record AxizDynamoCursorProperties(
    @Nullable String currentKeyId,
    @DefaultValue List<String> keyIds,
    @DefaultValue("cursor-key-") String secretPrefix,
    @DefaultValue("false") boolean encrypt) {
  public AxizDynamoCursorProperties {
    keyIds = List.copyOf(keyIds);
  }
}
