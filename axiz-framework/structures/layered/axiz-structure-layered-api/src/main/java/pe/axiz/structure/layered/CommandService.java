package pe.axiz.structure.layered;

/** Write operations with separate request and response types. Implementations are transactional. */
public interface CommandService<RequestT, ResponseT, I> {
  ResponseT create(RequestT request);

  /** Updates an existing authorized resource using the request's expected version. */
  ResponseT update(I id, RequestT request);

  /** Deletes an authorized resource; missing and forbidden objects both answer as absent. */
  void delete(I id);
}
