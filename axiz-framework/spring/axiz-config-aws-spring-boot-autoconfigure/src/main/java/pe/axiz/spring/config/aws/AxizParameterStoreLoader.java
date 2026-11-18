package pe.axiz.spring.config.aws;

import io.awspring.cloud.autoconfigure.config.parameterstore.ParameterStoreConfigDataLoader;
import io.awspring.cloud.autoconfigure.config.parameterstore.ParameterStoreConfigDataResource;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.config.ConfigData;
import org.springframework.boot.context.config.ConfigDataLoader;
import org.springframework.boot.context.config.ConfigDataLoaderContext;
import org.springframework.boot.logging.DeferredLogFactory;

/** Delegates parameter materialization without selecting another credentials provider. */
public final class AxizParameterStoreLoader
    implements ConfigDataLoader<AxizParameterStoreResource> {
  private final DeferredLogFactory logs;

  public AxizParameterStoreLoader(DeferredLogFactory logs) {
    this.logs = logs;
  }

  @Override
  public @Nullable ConfigData load(
      ConfigDataLoaderContext context, AxizParameterStoreResource resource) {
    return Cloud.load(logs, context, resource);
  }

  private static final class Cloud {
    private Cloud() {}

    static @Nullable ConfigData load(
        DeferredLogFactory logs,
        ConfigDataLoaderContext context,
        AxizParameterStoreResource resource) {
      return new ParameterStoreConfigDataLoader(logs)
          .load(context, (ParameterStoreConfigDataResource) resource.delegate());
    }
  }
}
