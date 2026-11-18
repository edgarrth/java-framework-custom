package pe.axiz.core.soap;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import pe.axiz.core.sensitive.MaskType;
import pe.axiz.core.sensitive.Sensitive;
import pe.axiz.core.sensitive.SensitivityLevel;

/**
 * Synchronous SOAP exchange. Implementations enforce timeout and response size limits, disable
 * automatic redirects, and propagate interruption. The application owns transport resources.
 */
@FunctionalInterface
public interface SoapTransport {
  Reply send(URI endpoint, SoapVersion version, String action, String envelope, Duration timeout)
      throws IOException, InterruptedException;

  /** Explicit payload access; diagnostic output contains no message data. */
  record Reply(
      int status, @Sensitive(level = SensitivityLevel.CRITICAL, mask = MaskType.FULL) String body) {
    public Reply {
      Objects.requireNonNull(body);
    }

    @Override
    public String toString() {
      return "Reply[status=" + status + "]";
    }
  }
}
