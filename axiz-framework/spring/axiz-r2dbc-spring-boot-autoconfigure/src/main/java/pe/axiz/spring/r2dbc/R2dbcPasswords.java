package pe.axiz.spring.r2dbc;

import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Option;
import org.reactivestreams.Publisher;
import pe.axiz.kit.r2dbc.DatabasePasswordProvider;
import reactor.core.publisher.Mono;

/** Aporta la contraseña de forma diferida para cada conexión física nueva. */
final class R2dbcPasswords {
  private R2dbcPasswords() {}

  /**
   * Asigna a la opción de contraseña un publicador evaluado para cada conexión nueva. Generar un
   * token es una firma local con una instantánea de credenciales precalentada. El refresco se
   * ejecuta con independencia de la adquisición de conexiones; una credencial expirada falla de
   * forma cerrada sin esperar E/S de red.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  static void apply(ConnectionFactoryOptions.Builder options, DatabasePasswordProvider provider) {
    Publisher<CharSequence> password = Mono.fromSupplier(provider::password);
    options.option((Option) ConnectionFactoryOptions.PASSWORD, password);
  }
}
