package pe.axiz.structure.layered;

/** Maintenance-resource API. Business money movements use explicit domain commands instead. */
public interface CrudService<RequestT, ResponseT, I>
    extends QueryService<ResponseT, I>, CommandService<RequestT, ResponseT, I> {}
