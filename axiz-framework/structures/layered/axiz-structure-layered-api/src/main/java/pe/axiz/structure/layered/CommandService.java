package pe.axiz.structure.layered;

/**
 * Operaciones de escritura con tipos de petición y de respuesta separados. Las implementaciones son
 * transaccionales.
 */
public interface CommandService<RequestT, ResponseT, I> {
  ResponseT create(RequestT request);

  /** Actualiza un recurso autorizado existente usando la versión esperada de la petición. */
  ResponseT update(I id, RequestT request);

  /**
   * Borra un recurso autorizado solo en la versión esperada; un recurso inexistente o prohibido
   * responde como ausente.
   */
  void delete(I id, String expectedVersion);
}
