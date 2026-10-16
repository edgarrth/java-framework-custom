package pe.axiz.payments.settlement.job;

import javax.sql.DataSource;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.storage.ObjectStorage;
import pe.axiz.framework.batch.spring.StorageRecordItemReader;

/**
 * The {@code settlement} job, one instance per file (the identifying {@code input.key} parameter):
 * a file already loaded is never loaded again and a failed run restarts after the last committed
 * chunk.
 *
 * <ol>
 *   <li>{@code validate}: control totals of the trailer, before loading anything.
 *   <li>{@code load}: detail lines into {@code settlement_line} in chunks (idempotent insert).
 *   <li>{@code summary}: totals per currency and status as metrics and as a JSON summary.
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
public class SettlementJobConfiguration {

  public static final String NAME = "settlement";
  public static final String INPUT_KEY = "input.key";
  static final int CHUNK_SIZE = 500;

  @Bean
  Job settlementJob(
      JobRepository jobRepository, Step validateStep, Step loadStep, Step summaryStep) {
    return new JobBuilder(NAME, jobRepository)
        .start(validateStep)
        .next(loadStep)
        .next(summaryStep)
        .build();
  }

  @Bean
  Step validateStep(
      JobRepository jobRepository, PlatformTransactionManager tx, ObjectStorage storage) {
    return new StepBuilder("validate", jobRepository)
        .tasklet(new ControlTotalsTasklet(storage), tx)
        .build();
  }

  @Bean
  Step loadStep(
      JobRepository jobRepository,
      PlatformTransactionManager tx,
      StorageRecordItemReader<SettlementLine> settlementReader,
      SettlementLineProcessor processor,
      JdbcBatchItemWriter<SettlementLine> settlementWriter) {
    return new StepBuilder("load", jobRepository)
        .<SettlementLine, SettlementLine>chunk(CHUNK_SIZE)
        .reader(settlementReader)
        .processor(processor)
        .writer(settlementWriter)
        .transactionManager(tx)
        .build();
  }

  @Bean
  Step summaryStep(
      JobRepository jobRepository,
      PlatformTransactionManager tx,
      DataSource dataSource,
      ObjectStorage storage,
      EventMetrics metrics) {
    return new StepBuilder("summary", jobRepository)
        .tasklet(new SettlementSummaryTasklet(JdbcClient.create(dataSource), storage, metrics), tx)
        .build();
  }

  @Bean
  @StepScope
  StorageRecordItemReader<SettlementLine> settlementReader(
      ObjectStorage storage, @Value("#{jobParameters['input.key']}") String inputKey) {
    return StorageRecordItemReader.named(
        "settlementReader", storage, inputKey, SettlementFile::reader);
  }

  @Bean
  @StepScope
  JdbcBatchItemWriter<SettlementLine> settlementWriter(
      DataSource dataSource, @Value("#{jobParameters['input.key']}") String inputKey) {
    return new JdbcBatchItemWriterBuilder<SettlementLine>()
        .dataSource(dataSource)
        .sql(
            "INSERT INTO settlement_line (end_to_end_id, transfer_id, creditor_cci, amount,"
                + " currency, status, settled_at, file_key) VALUES (:e2e, :transfer, :cci,"
                + " :amount, :currency, :status, :settledAt, :file)"
                + " ON CONFLICT (end_to_end_id) DO NOTHING")
        .itemSqlParameterSourceProvider(
            l ->
                new MapSqlParameterSource()
                    .addValue("e2e", l.endToEndId())
                    .addValue("transfer", l.transferId())
                    .addValue("cci", l.creditorCci())
                    .addValue("amount", l.amount())
                    .addValue("currency", l.currency())
                    .addValue("status", l.status())
                    .addValue("settledAt", l.settledAt())
                    .addValue("file", inputKey))
        .assertUpdates(false)
        .build();
  }
}
