package pe.axiz.payments.settlement.job;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import pe.axiz.core.fileformats.ControlTotals;
import pe.axiz.core.storage.ObjectStorage;
import pe.axiz.core.storage.StoredObject;
import pe.axiz.structure.batch.FixedWidthRecordReader;

/**
 * First step: reads the whole file and checks the trailer's record count and total before anything
 * is loaded, so a truncated or tampered file fails the job without partial data.
 */
public class ControlTotalsTasklet implements Tasklet {

  private final ObjectStorage storage;

  public ControlTotalsTasklet(ObjectStorage storage) {
    this.storage = storage;
  }

  @Override
  public @Nullable RepeatStatus execute(StepContribution contribution, ChunkContext context)
      throws Exception {
    String key =
        Objects.requireNonNull(
            (String)
                context
                    .getStepContext()
                    .getJobParameters()
                    .get(SettlementJobConfiguration.INPUT_KEY),
            "input.key job parameter");
    verify(storage, key);
    return RepeatStatus.FINISHED;
  }

  /** Reads the object and verifies its control totals; returns the number of detail lines. */
  static long verify(ObjectStorage storage, String key) throws Exception {
    StoredObject object =
        storage
            .get(key)
            .orElseThrow(() -> new IllegalStateException("Settlement file not found: " + key));
    ControlTotals totals = new ControlTotals();
    try (object;
        FixedWidthRecordReader<SettlementLine> reader = SettlementFile.reader(object.content())) {
      SettlementLine line;
      while ((line = reader.read()) != null) {
        totals.add(line.amount());
      }
      SettlementFile.Trailer trailer = SettlementFile.trailer(reader.trailer(), reader.line());
      totals.verify(trailer.count(), trailer.total(), reader.line());
    }
    return totals.records();
  }
}
