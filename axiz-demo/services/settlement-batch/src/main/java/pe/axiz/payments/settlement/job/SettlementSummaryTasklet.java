package pe.axiz.payments.settlement.job;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import pe.axiz.core.json.JsonWriter;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricEvent;
import pe.axiz.core.metrics.Outcome;
import pe.axiz.core.money.Money;
import pe.axiz.core.storage.ObjectMetadata;
import pe.axiz.core.storage.ObjectStorage;

/**
 * Último paso: totales por moneda y estado del archivo cargado, como métricas de negocio ({@code
 * settlement.file}, con montos) y como resumen JSON junto al archivo ({@code
 * out/settlement/<file>.summary.json}) para operaciones y conciliación. Una nueva ejecución sobre
 * un archivo ya cargado escribe de nuevo el resumen cuando falta ({@link #rewrite}).
 */
public class SettlementSummaryTasklet implements Tasklet {

  /** Totales de una moneda y un estado. */
  record Total(String currency, String status, long count, BigDecimal amount) {}

  private final JdbcClient jdbc;
  private final ObjectStorage storage;
  private final EventMetrics metrics;

  public SettlementSummaryTasklet(JdbcClient jdbc, ObjectStorage storage, EventMetrics metrics) {
    this.jdbc = jdbc;
    this.storage = storage;
    this.metrics = metrics;
  }

  @Override
  public @Nullable RepeatStatus execute(StepContribution contribution, ChunkContext context) {
    String key =
        Objects.requireNonNull(
            (String)
                context
                    .getStepContext()
                    .getJobParameters()
                    .get(SettlementJobConfiguration.INPUT_KEY),
            "input.key job parameter");
    publish(key, totals(key));
    return RepeatStatus.FINISHED;
  }

  /** Indica si el resumen de un archivo cargado está en el bucket. */
  public boolean exists(String fileKey) {
    return storage.head(summaryKey(fileKey)).isPresent();
  }

  /**
   * Vuelve a escribir, a partir de sus filas, el resumen de un archivo cargado previamente (por
   * ejemplo, cuando el bucket lo perdió). Las métricas no se registran dos veces: se registraron
   * cuando se cargó el archivo.
   */
  public void rewrite(String fileKey) {
    write(fileKey, totals(fileKey));
  }

  List<Total> totals(String key) {
    return jdbc.sql(
            "SELECT currency, status, COUNT(*) AS n, SUM(amount) AS total"
                + " FROM settlement_line WHERE file_key = :key"
                + " GROUP BY currency, status ORDER BY currency, status")
        .param("key", key)
        .query(
            (rs, _) ->
                new Total(
                    rs.getString("currency").strip(),
                    rs.getString("status"),
                    rs.getLong("n"),
                    rs.getBigDecimal("total")))
        .list();
  }

  void publish(String key, List<Total> totals) {
    for (Total t : totals) {
      metrics.record(
          MetricEvent.of("settlement.file")
              .amount(Money.of(t.amount(), t.currency()))
              .stage(t.status().toLowerCase(Locale.ROOT))
              .value((double) t.count())
              .outcome(Outcome.SUCCESS));
    }
    write(key, totals);
  }

  private void write(String key, List<Total> totals) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Total t : totals) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("currency", t.currency());
      row.put("status", t.status());
      row.put("count", t.count());
      row.put("amount", t.amount());
      rows.add(row);
    }
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("file", key);
    summary.put("totals", rows);
    byte[] json = JsonWriter.write(summary).getBytes(StandardCharsets.UTF_8);
    storage.put(
        summaryKey(key),
        new ByteArrayInputStream(json),
        ObjectMetadata.of("application/json", json.length));
  }

  static String summaryKey(String fileKey) {
    String name = fileKey.substring(fileKey.lastIndexOf('/') + 1);
    return "out/settlement/" + name + ".summary.json";
  }
}
