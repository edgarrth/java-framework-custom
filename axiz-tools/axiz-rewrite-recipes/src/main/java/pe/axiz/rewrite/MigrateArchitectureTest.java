package pe.axiz.rewrite;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeUtils;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Uses each owning schema 2 manifest, never guesses the service role from the old Style. */
public final class MigrateArchitectureTest extends ScanningRecipe<Map<Path, String>> {
  @Override
  public String getDisplayName() {
    return "Migrate axiz architecture tests";
  }

  @Override
  public String getDescription() {
    return "Replaces the former Style API using the owning project's migrated schema 2 manifest.";
  }

  @Override
  public Map<Path, String> getInitialValue(ExecutionContext ctx) {
    return new LinkedHashMap<>();
  }

  @Override
  public TreeVisitor<?, ExecutionContext> getScanner(Map<Path, String> projects) {
    return new TreeVisitor<Tree, ExecutionContext>() {
      @Override
      public Tree preVisit(Tree tree, ExecutionContext ctx) {
        stopAfterPreVisit();
        if (tree instanceof SourceFile source
            && source.getSourcePath().endsWith(".axiz/project.yaml")) {
          LoaderOptions options = new LoaderOptions();
          options.setAllowDuplicateKeys(false);
          options.setMaxAliasesForCollections(10);
          options.setCodePointLimit(1_000_000);
          Object document = new Yaml(new SafeConstructor(options)).load(source.printAll());
          if (!(document instanceof Map<?, ?> manifest)
              || !Integer.valueOf(2).equals(manifest.get("schema"))) {
            throw new IllegalArgumentException(
                "Run axiz upgrade to migrate project.yaml to schema 2 first");
          }
          if ("platform".equals(manifest.get("kind"))) return tree;
          String role =
              constant(
                  manifest.get("archetype"),
                  List.of(
                      "business", "core", "gateway", "ux", "support", "event-processor", "batch"));
          String stack = constant(manifest.get("stack"), List.of("servlet", "reactive"));
          String structure =
              constant(
                  manifest.get("structure"),
                  List.of("hexagonal", "layered", "eda", "bff", "batch"));
          String chain =
              ".archetype(pe.axiz.quality.archunit.Archetype."
                  + role
                  + ")"
                  + ".stack(pe.axiz.quality.archunit.Stack."
                  + stack
                  + ")"
                  + ".structure(pe.axiz.quality.archunit.Structure."
                  + structure
                  + ")";
          if (manifest.get("extensions") instanceof List<?> extensions && !extensions.isEmpty()) {
            chain +=
                ".extensions("
                    + String.join(
                        ", ",
                        extensions.stream()
                            .map(
                                e ->
                                    "pe.axiz.quality.archunit.Extension."
                                        + constant(e, List.of("cqrs")))
                            .toList())
                    + ")";
          }
          Path parent = Objects.requireNonNull(source.getSourcePath().getParent());
          Path grandparent = parent.getParent();
          Path root = grandparent == null ? Path.of("") : grandparent;
          projects.put(root, chain);
        }
        return tree;
      }
    };
  }

  @Override
  public TreeVisitor<?, ExecutionContext> getVisitor(Map<Path, String> projects) {
    return new JavaIsoVisitor<ExecutionContext>() {
      @Override
      public J.CompilationUnit visitCompilationUnit(J.CompilationUnit unit, ExecutionContext ctx) {
        J.CompilationUnit updated = super.visitCompilationUnit(unit, ctx);
        return updated.withImports(
            updated.getImports().stream()
                .filter(
                    imported ->
                        !(imported.isStatic()
                            && imported
                                .getQualid()
                                .printTrimmed(getCursor())
                                .equals("pe.axiz.quality.archunit.AxizArchitecture.verify")))
                .toList());
      }

      @Override
      public J.MethodInvocation visitMethodInvocation(
          J.MethodInvocation invocation, ExecutionContext ctx) {
        J.MethodInvocation method = super.visitMethodInvocation(invocation, ctx);
        var methodType = method.getMethodType();
        if (!method.getSimpleName().equals("verify")
            || method.getArguments().size() != 2
            || methodType == null
            || !TypeUtils.isOfClassType(
                methodType.getDeclaringType(), "pe.axiz.quality.archunit.AxizArchitecture"))
          return method;
        Path source = getCursor().firstEnclosingOrThrow(J.CompilationUnit.class).getSourcePath();
        String chain = null;
        int depth = -1;
        for (var project : projects.entrySet()) {
          Path root = project.getKey();
          if ((root.toString().isEmpty() || source.startsWith(root))
              && root.getNameCount() > depth) {
            depth = root.getNameCount();
            chain = project.getValue();
          }
        }
        if (chain == null)
          throw new IllegalStateException("Missing owning schema 2 project.yaml for " + source);
        maybeRemoveImport("pe.axiz.quality.archunit.Style");
        maybeAddImport("pe.axiz.quality.archunit.AxizArchitecture");
        return JavaTemplate.builder(
                "AxizArchitecture.of(#{any(java.lang.String)})" + chain + ".verify()")
            .imports("pe.axiz.quality.archunit.AxizArchitecture")
            .javaParser(JavaParser.fromJavaVersion().dependsOn(STUBS))
            .build()
            .apply(
                updateCursor(method),
                method.getCoordinates().replace(),
                method.getArguments().get(0));
      }
    };
  }

  private static String constant(@Nullable Object value, List<String> allowed) {
    String text = String.valueOf(value);
    if (!allowed.contains(text))
      throw new IllegalArgumentException("Invalid schema 2 architecture selector: " + text);
    return text.toUpperCase(Locale.ROOT).replace('-', '_');
  }

  private static final String[] STUBS = {
    "package pe.axiz.quality.archunit; public enum Archetype { BUSINESS, CORE, GATEWAY, UX, "
        + "SUPPORT, EVENT_PROCESSOR, BATCH }",
    "package pe.axiz.quality.archunit; public enum Stack { SERVLET, REACTIVE }",
    "package pe.axiz.quality.archunit; public enum Structure { HEXAGONAL, LAYERED, EDA, BFF, BATCH }",
    "package pe.axiz.quality.archunit; public enum Extension { CQRS }",
    """
      package pe.axiz.quality.archunit;
      public class AxizArchitecture {
        public static AxizArchitecture of(String pkg) { return new AxizArchitecture(); }
        public AxizArchitecture archetype(Archetype role) { return this; }
        public AxizArchitecture stack(Stack stack) { return this; }
        public AxizArchitecture structure(Structure structure) { return this; }
        public AxizArchitecture extensions(Extension... extensions) { return this; }
        public void verify() {}
      }
      """
  };
}
