package pe.axiz.spring.dynamodb;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Shared cursor keys from secrets; retain old ids until clients can restart pagination. */
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
