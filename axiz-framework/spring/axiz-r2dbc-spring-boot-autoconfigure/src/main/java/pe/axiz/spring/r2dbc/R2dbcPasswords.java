package pe.axiz.spring.r2dbc;

import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Option;
import org.reactivestreams.Publisher;
import pe.axiz.kit.r2dbc.DatabasePasswordProvider;
import reactor.core.publisher.Mono;

/** Supplies the password lazily for each new physical connection. */
final class R2dbcPasswords {
  private R2dbcPasswords() {}

  /**
   * Sets the password option to a publisher evaluated for every new connection. Generating a token
   * is local signing with a warmed credential snapshot. Refresh runs independently of connection
   * acquisition; an expired credential fails closed without waiting on network I/O.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  static void apply(ConnectionFactoryOptions.Builder options, DatabasePasswordProvider provider) {
    Publisher<CharSequence> password = Mono.fromSupplier(provider::password);
    options.option((Option) ConnectionFactoryOptions.PASSWORD, password);
  }
}
