package pe.axiz.spring.config.aws;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.config.ConfigDataResource;

/** Resource delegated to Cloud AWS after managed bootstrap policy has been applied. */
public final class AxizParameterStoreResource extends ConfigDataResource {
  private final Object delegate;

  AxizParameterStoreResource(Object delegate, boolean optional) {
    super(optional);
    this.delegate = Objects.requireNonNull(delegate);
  }

  Object delegate() {
    return delegate;
  }

  @Override
  public boolean equals(@Nullable Object other) {
    return other instanceof AxizParameterStoreResource resource
        && delegate.equals(resource.delegate);
  }

  @Override
  public int hashCode() {
    return delegate.hashCode();
  }

  @Override
  public String toString() {
    return delegate.toString();
  }
}
