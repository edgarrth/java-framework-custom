package pe.axiz.codegen.openapi;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * Cargador YAML para contratos no confiables: constructor seguro (sin tipos arbitrarios), límites
 * de alias y de tamaño, sin claves duplicadas y con las fechas conservadas como texto para que los
 * ejemplos no se reescriban.
 */
public final class SafeYaml {

  private SafeYaml() {}

  public static Yaml create(int maxCodePoints) {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(20);
    options.setCodePointLimit(maxCodePoints);
    options.setNestingDepthLimit(64);
    DumperOptions dumper = new DumperOptions();
    return new Yaml(
        new SafeConstructor(options),
        new Representer(dumper),
        dumper,
        options,
        new NoTimestampResolver());
  }

  /** Resolver sin la regla de marcas de tiempo. */
  static final class NoTimestampResolver extends Resolver {
    @Override
    protected void addImplicitResolvers() {
      addImplicitResolver(Tag.BOOL, BOOL, "yYnNtTfFoO", 10);
      addImplicitResolver(Tag.INT, INT, "-+0123456789");
      addImplicitResolver(Tag.FLOAT, FLOAT, "-+0123456789.");
      addImplicitResolver(Tag.MERGE, MERGE, "<", 10);
      addImplicitResolver(Tag.NULL, NULL, "~nN\0", 10);
      addImplicitResolver(Tag.NULL, EMPTY, null, 10);
      addImplicitResolver(Tag.YAML, YAML, "!&*", 10);
    }
  }
}
