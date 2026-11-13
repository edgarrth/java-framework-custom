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
import pe.axiz.core.commons.json.JsonWriter;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricEvent;
import pe.axiz.core.metrics.Outcome;
import pe.axiz.core.money.Money;
import pe.axiz.core.storage.ObjectMetadata;
import pe.axiz.core.storage.ObjectStorage;

/**
 * Last step: totals per currency and status of the loaded file, as business metrics ({@code
 * settlement.file}, with amounts) and as a JSON summary next to the file ({@code
 * out/settlement/<file>.summary.json}) for operations and reconciliation. A rerun of a file that
 * was already loaded writes the summary again when it is missing ({@link #rewrite}).
 */
public class SettlementSummaryTasklet implements Tasklet {

  /** Totals of one currency and status. */
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

  /** Whether the summary of a loaded file is in the bucket. */
  public boolean exists(String fileKey) {
    return storage.head(summaryKey(fileKey)).isPresent();
  }

  /**
   * Writes the summary of a file that was loaded before again, from its rows (for example after the
   * bucket lost it). Metrics are not recorded twice: they were recorded when the file was loaded.
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
