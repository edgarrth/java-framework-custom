package pe.axiz.spring.r2dbc;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.core.crypto.EncryptionContext;

/**
 * Aurora PostgreSQL over R2DBC ({@code axiz.r2dbc.*}). The connection itself is configured with the
 * standard {@code spring.r2dbc.*} properties (URL in the ConfigMap, password in the secret store or
 * an IAM token).
 *
 * @param reader optional reader endpoint for read-only queries
 * @param flywayFromR2dbc derive {@code spring.flyway.url/user/password} from {@code spring.r2dbc.*}
 *     when they are not set (Flyway migrates over JDBC)
 * @param auditing auditing of {@code AuditableRow}
 */
@ConfigurationProperties("axiz.r2dbc")
public record AxizR2dbcProperties(
    @DefaultValue Reader reader,
    @DefaultValue("true") boolean flywayFromR2dbc,
    @DefaultValue Auditing auditing,
    @Nullable String encryptionContext) {
  @ConstructorBinding
  public AxizR2dbcProperties {
    if (encryptionContext != null) EncryptionContext.require(encryptionContext);
  }

  /**
   * Reader endpoint of the Aurora cluster for queries that tolerate replica lag (CQRS read side,
   * reports). Exposed as the {@link ReaderDatabase} bean, never as the primary connection.
   *
   * @param url R2DBC URL of the reader endpoint, e.g. {@code
   *     r2dbc:postgresql://cluster-ro.example:5432/app}
   * @param maxSize maximum connections of the reader pool
   */
  public record Reader(@Nullable String url, @DefaultValue("10") int maxSize) {}

  /**
   * Auditing.
   *
   * @param enabled whether {@code createdBy}, {@code createdAt}, {@code updatedBy} and {@code
   *     updatedAt} are filled automatically
   */
  public record Auditing(@DefaultValue("true") boolean enabled) {}
}
