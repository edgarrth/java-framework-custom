package pe.axiz.spring.r2dbc;

import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Option;
import io.r2dbc.spi.ValidationDepth;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.r2dbc.ConnectionFactoryBuilder;
import org.springframework.boot.r2dbc.ConnectionFactoryDecorator;
import pe.axiz.kit.r2dbc.DatabasePasswordProvider;

/** Builds the pooled connection factory of the reader endpoint. */
final class ReaderConnections {

  private ReaderConnections() {}

  /** {@code r2dbc:postgresql://...} becomes {@code r2dbc:pool:postgresql://...}. */
  static String pooled(String url) {
    if (url.startsWith("r2dbc:pool:")) {
      return url;
    }
    if (!url.startsWith("r2dbc:")) {
      throw new IllegalArgumentException("axiz.r2dbc.reader.url must be an R2DBC URL");
    }
    return "r2dbc:pool:" + url.substring("r2dbc:".length());
  }

  static ConnectionFactory create(
      String url,
      int maxSize,
      @Nullable String username,
      @Nullable String password,
      @Nullable DatabasePasswordProvider iam,
      Map<String, String> driverProperties,
      List<ConnectionFactoryDecorator> decorators) {
    ConnectionFactoryBuilder builder =
        ConnectionFactoryBuilder.withUrl(pooled(url))
            .configure(
                o -> {
                  o.option(Option.valueOf("maxSize"), maxSize);
                  o.option(Option.valueOf("initialSize"), Math.min(2, maxSize));
                  o.option(Option.valueOf("maxLifeTime"), Duration.ofMinutes(10));
                  o.option(Option.valueOf("maxIdleTime"), Duration.ofMinutes(5));
                  o.option(Option.valueOf("maxAcquireTime"), Duration.ofSeconds(5));
                  o.option(Option.valueOf("validationQuery"), "SELECT 1");
                  o.option(Option.valueOf("validationDepth"), ValidationDepth.REMOTE);
                  // the same driver options as the writer (sslMode, applicationName...)
                  driverProperties.forEach((k, v) -> o.option(Option.valueOf(k), v));
                })
            .decorators(decorators);
    if (username != null) {
      builder.username(username);
    }
    if (iam != null) {
      builder.configure(o -> IamTokens.apply(o, iam));
    } else if (password != null) {
      builder.password(password);
    }
    return builder.build();
  }
}
