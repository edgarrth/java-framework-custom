package pe.axiz.structure.batch;

import java.time.Duration;

/**
 * Summary of a finished job used for metrics, audit and the CronJob exit code.
 *
 * @param job job name
 * @param status final status ({@code COMPLETED}, {@code FAILED}, ...)
 * @param read records read
 * @param written records written
 * @param skipped records skipped
 * @param duration elapsed time
 */
public record JobOutcome(
    String job, String status, long read, long written, long skipped, Duration duration) {

  public boolean successful() {
    return "COMPLETED".equals(status);
  }

  /** Process exit code: 0 on success, 1 otherwise (Kubernetes marks the CronJob run as failed). */
  public int exitCode() {
    return successful() ? 0 : 1;
  }
}
