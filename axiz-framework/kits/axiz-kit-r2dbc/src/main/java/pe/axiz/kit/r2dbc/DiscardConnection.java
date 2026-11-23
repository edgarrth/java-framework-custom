package pe.axiz.kit.r2dbc;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ValidationDepth;
import io.r2dbc.spi.Wrapped;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/** Cierra la conexión física antes de liberar un wrapper con una transacción incierta. */
final class DiscardConnection {
  private DiscardConnection() {}

  static Mono<Void> close(Connection connection) {
    return Mono.defer(
        () -> {
          Connection physical = connection;
          Set<Connection> visited = Collections.newSetFromMap(new IdentityHashMap<>());
          while (physical instanceof Wrapped<?> wrapper) {
            if (!visited.add(physical))
              return Mono.error(new IllegalStateException("Cyclic R2DBC connection wrapper"));
            Object delegate = wrapper.unwrap();
            if (!(delegate instanceof Connection next) || next == physical) break;
            physical = next;
          }
          // r2dbc-pool valida al liberar e invalida una conexión física cerrada.
          if (physical == connection) return Mono.from(physical.close());
          return closePhysical(physical)
              .doOnError(
                  failure ->
                      LoggerFactory.getLogger(DiscardConnection.class)
                          .error(
                              "AXIZ_R2DBC_CONNECTION_QUARANTINED: pool capacity reduced; inspect pool health"))
              .then(Mono.defer(() -> Mono.from(connection.close())));
        });
  }

  private static Mono<Void> closePhysical(Connection physical) {
    return Mono.defer(() -> Mono.from(physical.close()))
        .onErrorResume(
            failure ->
                Mono.defer(() -> Mono.from(physical.validate(ValidationDepth.LOCAL)))
                    .defaultIfEmpty(true)
                    .flatMap(
                        valid -> {
                          if (!valid)
                            return Mono
                                .empty(); // La liberación al pool invalidará el delegado cerrado.
                          // Un cierre fallido con una conexión aún viva no puede reciclarse de
                          // forma segura mediante el SPI.
                          return Mono.error(
                              new IllegalStateException(
                                  "R2DBC physical connection could not be discarded"));
                        }));
  }
}
