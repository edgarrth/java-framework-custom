package pe.axiz.extension.cqrs;

/**
 * Autorización a nivel de objeto de un tipo de comando o consulta: decide si quien llama puede
 * actuar sobre los recursos que nombra la petición (cliente, cuenta, orden), más allá de las
 * autoridades de {@link RequiresAuthority}. Las políticas son beans; el pipeline ejecuta cada
 * política cuyo {@link #requestType()} coincide con la petición, en la etapa de autorización, antes
 * de los replays de idempotencia, las transacciones y los handlers.
 *
 * <p>Se deniega con {@code AccessException} (403), o con {@code NotFoundException} (404) cuando no
 * debe revelarse la existencia del recurso de otro llamador. Las políticas imperativas pueden leer
 * estado (por ejemplo, el dueño de una orden). Los buses reactivos requieren {@link
 * ReactiveAccessPolicy} y rechazan este contrato imperativo.
 *
 * @param <R> tipo de petición (o un supertipo compartido por varias peticiones)
 */
public interface AccessPolicy<R> {

  Class<R> requestType();

  void check(R request, Caller caller);
}
