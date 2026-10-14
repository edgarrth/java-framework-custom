package pe.axiz.spring.test;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import reactor.blockhound.BlockHound;

/**
 * Fails reactive tests that block an event-loop thread. Mandatory for reactive services:
 * {@code @ExtendWith(BlockHoundExtension.class)}.
 */
public class BlockHoundExtension implements BeforeAllCallback {

  private static final AtomicBoolean INSTALLED = new AtomicBoolean();

  @Override
  public void beforeAll(ExtensionContext context) {
    if (INSTALLED.compareAndSet(false, true)) {
      BlockHound.builder()
          // logging back-ends may touch the file system on first use
          .allowBlockingCallsInside("ch.qos.logback.classic.Logger", "callAppenders")
          .install();
    }
  }
}
