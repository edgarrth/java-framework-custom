package pe.axiz.rewrite;

import java.util.ArrayList;
import java.util.Objects;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.xml.XPathMatcher;
import org.openrewrite.xml.XmlIsoVisitor;
import org.openrewrite.xml.tree.Content;
import org.openrewrite.xml.tree.Xml;

/** Moves production adapters to runtime without promoting test fixtures into the application. */
public final class AdaptersAsRuntime extends Recipe {
  private static final XPathMatcher DIRECT = new XPathMatcher("/project/dependencies/dependency");
  private static final XPathMatcher PROFILE =
      new XPathMatcher("/project/profiles/profile/dependencies/dependency");

  @Override
  public String getDisplayName() {
    return "Use axiz adapters at runtime";
  }

  @Override
  public String getDescription() {
    return "Changes direct compile adapter dependencies to runtime while preserving tests, "
        + "provided dependencies, plugins and dependency management.";
  }

  @Override
  public TreeVisitor<?, ExecutionContext> getVisitor() {
    return new XmlIsoVisitor<ExecutionContext>() {
      @Override
      public Xml.Tag visitTag(Xml.Tag tag, ExecutionContext context) {
        Xml.Tag updated = super.visitTag(tag, context);
        if (!getCursor()
                .firstEnclosingOrThrow(Xml.Document.class)
                .getSourcePath()
                .endsWith("pom.xml")
            || !(DIRECT.matches(getCursor()) || PROFILE.matches(getCursor()))
            || !updated.getChildValue("groupId").orElse("").equals("pe.axiz")
            || !updated.getChildValue("artifactId").orElse("").startsWith("axiz-adapter-"))
          return updated;
        String scope = updated.getChildValue("scope").orElse("");
        if (!scope.isEmpty() && !scope.equals("compile")) return updated;
        if (updated.getChild("scope").isPresent())
          return updated.withChildValue("scope", "runtime");
        var contents = new ArrayList<Content>(Objects.requireNonNull(updated.getContent()));
        contents.add(
            Xml.Tag.build("<scope>runtime</scope>")
                .withPrefix(updated.getChildren().getLast().getPrefix()));
        return updated.withContent(contents);
      }
    };
  }
}
