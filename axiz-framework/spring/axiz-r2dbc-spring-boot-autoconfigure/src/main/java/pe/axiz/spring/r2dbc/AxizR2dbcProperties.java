package pe.axiz.spring.r2dbc;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.core.crypto.EncryptionContext;

/**
 * Aurora PostgreSQL sobre R2DBC ({@code axiz.r2dbc.*}). La conexión en sí se configura con las
 * propiedades estándar {@code spring.r2dbc.*} (URL en el ConfigMap, contraseña en el almacén de
 * secretos o un token IAM).
 *
 * @param reader endpoint de lectura opcional para consultas de solo lectura
 * @param flywayFromR2dbc deriva {@code spring.flyway.url/user/password} de {@code spring.r2dbc.*}
 *     cuando no están configurados (Flyway migra sobre JDBC)
 * @param auditing auditoría de {@code AuditableRow}
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
   * Endpoint de lectura del clúster Aurora para consultas que toleran el retraso de las réplicas
   * (lado de lectura de CQRS, informes). Se expone como el bean {@link ReaderDatabase}, nunca como
   * la conexión principal.
   *
   * @param url URL R2DBC del endpoint de lectura, p. ej. {@code
   *     r2dbc:postgresql://cluster-ro.example:5432/app}
   * @param maxSize máximo de conexiones del pool de lectura
   */
  public record Reader(@Nullable String url, @DefaultValue("10") int maxSize) {}

  /**
   * Auditoría.
   *
   * @param enabled indica si {@code createdBy}, {@code createdAt}, {@code updatedBy} y {@code
   *     updatedAt} se completan automáticamente
   */
  public record Auditing(@DefaultValue("true") boolean enabled) {}
}
