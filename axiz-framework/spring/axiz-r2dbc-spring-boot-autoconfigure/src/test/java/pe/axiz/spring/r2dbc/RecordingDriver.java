package pe.axiz.spring.r2dbc;

import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.ConnectionFactoryProvider;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import pe.axiz.test.support.r2dbc.FakeR2dbc;

/**
 * Driver de prueba para URLs {@code r2dbc:axiztest://...}: registra las opciones que recibe, de
 * modo que una prueba puede ver lo que llega al driver real detrás del pool.
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
