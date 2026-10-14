package pe.axiz.framework.hexagonal;

/**
 * An application use case (inbound port implementation). It runs in one transaction; domain events
 * raised while it runs are published when that transaction commits.
 *
 * @param <I> input (a command or query record)
 * @param <O> output
 */
@FunctionalInterface
public interface UseCase<I, O> {

  O execute(I input);
}
