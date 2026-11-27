package pe.axiz.core.soap;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import pe.axiz.core.sensitive.MaskType;
import pe.axiz.core.sensitive.Sensitive;
import pe.axiz.core.sensitive.SensitivityLevel;

/**
 * Intercambio SOAP síncrono. Las implementaciones aplican límites de timeout y de tamaño de
 * respuesta, desactivan las redirecciones automáticas y propagan la interrupción. La aplicación es
 * dueña de los recursos de transporte.
 */
@FunctionalInterface
public interface SoapTransport {
  Reply send(URI endpoint, SoapVersion version, String action, String envelope, Duration timeout)
      throws IOException, InterruptedException;

  /** Acceso explícito al payload; la salida de diagnóstico no contiene datos del mensaje. */
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
