package pe.axiz.core.secrets;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.concurrent.Stages;

/** Fuentes nativas ordenadas. Los fallos se propagan; solo la ausencia avanza a otra fuente. */
public final class CompositeAsyncSecretResolver implements AsyncSecretResolver {
  private final List<AsyncSecretResolver> delegates;

  public CompositeAsyncSecretResolver(List<AsyncSecretResolver> delegates) {
    if (delegates.size() > 64) throw new IllegalArgumentException("Too many secret sources");
    this.delegates = List.copyOf(delegates);
  }

  @Override
  public CompletionStage<Optional<SecretValue>> resolve(String name) {
    var resolution = new Resolution(name);
    // Se captura el contexto antes de iniciar cualquier fuente; un fallo no debe iniciar E/S
    // remota.
    Stages.observe(
        CompletableFuture.<Void>completedFuture(null),
        (ignored, failure) -> resolution.advance(0),
        resolution::fail);
    return resolution;
  }

  private final class Resolution extends CompletableFuture<Optional<SecretValue>> {
    private final String name;
    private final AtomicReference<@Nullable CompletableFuture<?>> active = new AtomicReference<>();

    private Resolution(String name) {
      this.name = name;
    }

    @Override
    public boolean cancel(boolean interrupt) {
      boolean cancelled = super.cancel(interrupt);
      var operation = active.get();
      if (cancelled && operation != null) operation.cancel(interrupt);
      return cancelled;
    }

    private void fail(Throwable failure) {
      if (completeExceptionally(Stages.unwrap(failure))) {
        var operation = active.get();
        if (operation != null) operation.cancel(true);
      }
    }

    @SuppressWarnings(
        "FutureReturnValueIgnored") // La limpieza de propiedad de abajo no puede fallar.
    private void advance(int index) {
      if (isDone()) return;
      if (index == delegates.size()) {
        complete(Optional.empty());
        return;
      }
      var source = Stages.call(() -> delegates.get(index).resolve(name));
      active.set(source.toCompletableFuture());
      var transferred = new AtomicBoolean();
      var received = new AtomicReference<@Nullable SecretValue>();
      // Siempre se observa la propiedad, incluso cuando el observador contextual no puede entrar en
      // su scope.
      source.whenComplete(
          (value, failure) -> {
            if (value != null && value.isPresent()) {
              SecretValue secret = value.get();
              received.set(secret);
              if (isDone() && !transferred.get()) secret.close();
            }
          });
      Stages.observe(
          source,
          (value, failure) -> {
            if (isDone()) {
              if (value != null) value.ifPresent(SecretValue::close);
            } else if (failure != null) {
              fail(failure);
            } else if (value == null) {
              fail(new IllegalStateException("Secret source returned no result"));
            } else if (value.isPresent()) {
              // Se fija antes de la finalización: el observador incondicional no debe cerrar un
              // resultado aceptado.
              transferred.set(true);
              if (!complete(value)) value.ifPresent(SecretValue::close);
            } else advance(index + 1);
          },
          failure -> {
            fail(failure);
            SecretValue secret = received.get();
            if (secret != null && !transferred.get()) secret.close();
          });
      if (isCancelled()) source.toCompletableFuture().cancel(true);
    }
  }
}
