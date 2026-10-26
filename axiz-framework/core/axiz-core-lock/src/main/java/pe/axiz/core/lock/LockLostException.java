package pe.axiz.core.lock;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.errors.AxizException;

/** The lease of a lock ended before the work under it finished ({@link LockErrors#LOST}). */
public class LockLostException extends AxizException {

  private static final long serialVersionUID = 1L;

  public LockLostException(String resource, @Nullable Throwable cause) {
    super(
        LockErrors.LOST,
        "lock on " + resource + " lost before the work finished",
        Map.of("resource", resource),
        List.of(),
        null,
        cause);
  }
}
