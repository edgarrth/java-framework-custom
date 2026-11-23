package pe.axiz.spring.security;

import java.util.concurrent.TimeUnit;

/** Un intento de red en curso por endpoint configurado, con backoff monotónico ante fallos. */
final class IssuerRequestGate {
  private boolean running;
  private int failures;
  private long retryAt;

  synchronized void enter() {
    long remaining = retryAt - System.nanoTime();
    if (running || (failures > 0 && remaining > 0))
      throw new IssuerUnavailableException(
          Math.max(1, TimeUnit.NANOSECONDS.toSeconds(remaining) + 1));
    running = true;
  }

  synchronized void success() {
    running = false;
    failures = 0;
    retryAt = 0;
  }

  synchronized IssuerUnavailableException failure() {
    running = false;
    failures = Math.min(7, failures + 1);
    long seconds = Math.min(60, 1L << (failures - 1));
    retryAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    return new IssuerUnavailableException(seconds);
  }
}
