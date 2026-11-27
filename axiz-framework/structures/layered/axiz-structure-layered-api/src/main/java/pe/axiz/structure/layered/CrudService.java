package pe.axiz.structure.layered;

/**
 * API de recursos de mantenimiento. Los movimientos de dinero de negocio usan en cambio comandos de
 * dominio explícitos.
 */
public interface CrudService<RequestT, ResponseT, I>
    extends QueryService<ResponseT, I>, CommandService<RequestT, ResponseT, I> {}
