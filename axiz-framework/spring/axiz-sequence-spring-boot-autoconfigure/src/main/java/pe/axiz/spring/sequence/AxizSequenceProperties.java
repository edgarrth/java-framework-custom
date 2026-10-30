package pe.axiz.spring.sequence;

import java.time.ZoneId;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pe.axiz.core.sequence.ResetPolicy;
import pe.axiz.spring.foundation.jdbc.SchemaInitializer;

/**
 * Business sequences ({@code axiz.sequence.*}), e.g. operation numbers {@code OP-2026-00001234}.
 *
 * @param store {@code jdbc} (Aurora) or {@code dynamodb}
 * @param table counters table
 * @param initializeSchema when the JDBC table is created by the starter
 * @param sequences sequences by name
 */
@ConfigurationProperties("axiz.sequence")
public record AxizSequenceProperties(
    @DefaultValue("jdbc") String store,
    @DefaultValue("axiz_sequence") String table,
    @DefaultValue("EMBEDDED") SchemaInitializer.Mode initializeSchema,
    @DefaultValue Map<String, Spec> sequences) {
  public AxizSequenceProperties {
    sequences = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(sequences));
  }

  /**
   * One sequence.
   *
   * @param prefix fixed prefix
   * @param width zero-padded digits
   * @param reset reset period
   * @param separator separator between prefix, period and number
   * @param zone zone of the period
   * @param blockSize values reserved per round trip (gaps possible on restart)
   */
  public record Spec(
      @DefaultValue("") String prefix,
      @DefaultValue("8") int width,
      @DefaultValue("NEVER") ResetPolicy reset,
      @DefaultValue("-") String separator,
      @DefaultValue("America/Lima") ZoneId zone,
      @DefaultValue("1") int blockSize) {}
}
