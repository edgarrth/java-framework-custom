package pe.axiz.test.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;

/**
 * Adapts an in-memory protocol fake to an SDK's asynchronous interface for deterministic contract
 * tests.
 */
public final class ImmediateAsyncFakes {
  private ImmediateAsyncFakes() {}

  public static <A> A adapt(Class<A> asynchronous, Object inMemoryFake) {
    return asynchronous.cast(
        Proxy.newProxyInstance(
            asynchronous.getClassLoader(),
            new Class<?>[] {asynchronous},
            (proxy, method, args) -> {
              if (method.getDeclaringClass() == Object.class)
                return objectMethod(proxy, method, args);
              if (!CompletionStage.class.isAssignableFrom(method.getReturnType()))
                return invoke(inMemoryFake, method, args);
              CompletableFuture<Object> future = new CompletableFuture<>();
              try {
                future.complete(invoke(inMemoryFake, method, args));
              } catch (Throwable failure) {
                future.completeExceptionally(failure);
              }
              if (method.getReturnType().isInstance(future)) return future;
              return Proxy.newProxyInstance(
                  method.getReturnType().getClassLoader(),
                  new Class<?>[] {method.getReturnType()},
                  (p, m, a) -> {
                    if (m.getDeclaringClass() == Object.class) return objectMethod(p, m, a);
                    if (m.getName().equals("getError")) {
                      if (!future.isCompletedExceptionally()) return null;
                      try {
                        future.join();
                        return null;
                      } catch (CompletionException failure) {
                        return Objects.requireNonNull(failure.getCause()).toString();
                      }
                    }
                    // The protocol fake already finished before this future was returned.
                    if (m.getName().equals("await")) return future.isDone();
                    try {
                      return m.invoke(future, a);
                    } catch (InvocationTargetException error) {
                      throw Objects.requireNonNull(error.getCause());
                    }
                  });
            }));
  }

  private static Object objectMethod(Object proxy, Method method, Object @Nullable [] args) {
    return switch (method.getName()) {
      case "equals" -> proxy == Objects.requireNonNull(args)[0];
      case "hashCode" -> System.identityHashCode(proxy);
      case "toString" ->
          "ImmediateAsyncFake@" + Integer.toHexString(System.identityHashCode(proxy));
      default -> throw new UnsupportedOperationException(method.getName());
    };
  }

  private static @Nullable Object invoke(
      Object target, Method method, Object @Nullable [] arguments) throws Throwable {
    Method equivalent = target.getClass().getMethod(method.getName(), method.getParameterTypes());
    if (!equivalent.trySetAccessible())
      throw new IllegalArgumentException("Fake method is not accessible: " + method.getName());
    try {
      return equivalent.invoke(target, arguments);
    } catch (InvocationTargetException failure) {
      throw Objects.requireNonNull(failure.getCause());
    }
  }
}
