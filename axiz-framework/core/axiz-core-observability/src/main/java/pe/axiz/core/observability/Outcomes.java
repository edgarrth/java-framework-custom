package pe.axiz.core.observability;

import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Classifies the result of an operation into the standard outcome values. */
public final class Outcomes {

  public static final String SUCCESS = "success";
  public static final String REJECTED = "rejected";
  public static final String ERROR = "error";
  public static final String TIMEOUT = "timeout";
  public static final String CANCELLED = "cancelled";

  private Outcomes() {}

  /**
   * Outcome for a failure. Business rejections are recognized through the given classifier (the
   * errors capability recognizes business, validation and security errors).
   *
   * @param error the failure, null meaning success
   * @param isRejection whether the failure is an expected business rejection
   * @return outcome value
   */
  public static String of(@Nullable Throwable error, Function<Throwable, Boolean> isRejection) {
    if (error == null) {
      return SUCCESS;
    }
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof TimeoutException || t.getClass().getSimpleName().contains("Timeout")) {
        return TIMEOUT;
      }
      if (t instanceof CancellationException || t instanceof InterruptedException) {
        return CANCELLED;
      }
      if (Boolean.TRUE.equals(isRejection.apply(t))) {
        return REJECTED;
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return ERROR;
  }
}
