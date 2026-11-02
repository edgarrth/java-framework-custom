package pe.axiz.structure.layered;

/**
 * Read and write operations of a simple resource (catalogs, parameters, maintenance tables).
 *
 * @param <T> resource type
 * @param <I> id type
 */
public interface CrudService<T, I> extends QueryService<T, I>, CommandService<T, I> {}
