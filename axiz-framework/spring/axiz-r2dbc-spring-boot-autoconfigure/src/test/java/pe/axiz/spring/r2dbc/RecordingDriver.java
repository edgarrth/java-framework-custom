package pe.axiz.spring.r2dbc;

import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.ConnectionFactoryProvider;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import pe.axiz.test.support.r2dbc.FakeR2dbc;

/**
 * Test driver for {@code r2dbc:axiztest://...} URLs: records the options it receives, so a test can
 * see what reaches the real driver behind the pool.
 */
public final class RecordingDriver implements ConnectionFactoryProvider {

  static final String DRIVER = "axiztest";
  static final List<ConnectionFactoryOptions> CREATED = new CopyOnWriteArrayList<>();

  @Override
  public ConnectionFactory create(ConnectionFactoryOptions options) {
    CREATED.add(options);
    return new FakeR2dbc();
  }

  @Override
  public boolean supports(ConnectionFactoryOptions options) {
    return DRIVER.equals(options.getValue(ConnectionFactoryOptions.DRIVER));
  }

  @Override
  public String getDriver() {
    return DRIVER;
  }
}
