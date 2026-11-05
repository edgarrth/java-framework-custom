package pe.axiz.spring.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.crypto.AesGcm;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.core.secrets.SecretValue;
import pe.axiz.kit.dynamodb.CursorCodec;
import pe.axiz.kit.dynamodb.InvalidCursorException;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

class CursorConfigurationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizDynamoCursorAutoConfiguration.class));

  @Test
  void keysAreExplicitAndResolvedOnlyDuringStartup() {
    runner.run(ctx -> assertThat(ctx).doesNotHaveBean(CursorCodec.class));
    runner
        .withPropertyValues("axiz.dynamodb.cursor.current-key-id=v1")
        .withBean(SecretResolver.class, () -> name -> Optional.empty())
        .run(ctx -> assertThat(ctx).hasFailed());
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    String key = Base64.getEncoder().encodeToString(AesGcm.newKey().getEncoded());
    runner
        .withPropertyValues(
            "axiz.dynamodb.cursor.current-key-id=v1", "axiz.dynamodb.cursor.encrypt=true")
        .withBean(
            SecretResolver.class,
            () ->
                name -> {
                  calls.incrementAndGet();
                  assertEquals("cursor-key-v1", name);
                  return Optional.of(SecretValue.of(key));
                })
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              var codec = ctx.getBean(CursorCodec.class);
              var last = Map.of("pk", AttributeValue.fromS("tenant"));
              String cursor = codec.encode(last, "table/tenant");
              assertEquals(last, codec.decode(cursor, "table/tenant"));
              assertThrows(
                  InvalidCursorException.class, () -> codec.decode(cursor, "other/tenant"));
              assertEquals(1, calls.get());
            });
  }
}
