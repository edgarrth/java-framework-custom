package pe.axiz.rewrite;

import static org.openrewrite.xml.Assertions.xml;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openrewrite.test.RewriteTest;

class AdaptersAsRuntimeTest implements RewriteTest {
  @Test
  void productionCompileAdaptersBecomeRuntimeButKitsStayCompile() {
    rewriteRun(
        spec -> spec.recipe(new AdaptersAsRuntime()),
        xml(
            """
        <project>
          <dependencies>
            <dependency>
              <groupId>pe.axiz</groupId>
              <artifactId>axiz-adapter-storage-s3</artifactId>
            </dependency>
            <dependency>
              <groupId>pe.axiz</groupId>
              <artifactId>axiz-kit-jpa</artifactId>
              <scope>compile</scope>
            </dependency>
          </dependencies>
        </project>
        """,
            """
        <project>
          <dependencies>
            <dependency>
              <groupId>pe.axiz</groupId>
              <artifactId>axiz-adapter-storage-s3</artifactId>
              <scope>runtime</scope>
            </dependency>
            <dependency>
              <groupId>pe.axiz</groupId>
              <artifactId>axiz-kit-jpa</artifactId>
              <scope>compile</scope>
            </dependency>
          </dependencies>
        </project>
        """,
            source -> source.path("pom.xml")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"test", "provided", "runtime", "${adapter.scope}"})
  void existingNonCompileScopeIsPreserved(String scope) {
    rewriteRun(
        spec -> spec.recipe(new AdaptersAsRuntime()),
        xml(
            """
        <project>
          <dependencies>
            <dependency>
              <groupId>pe.axiz</groupId>
              <artifactId>axiz-adapter-cache-redis</artifactId>
              <scope>%s</scope>
            </dependency>
          </dependencies>
        </project>
        """
                .formatted(scope),
            source -> source.path("pom.xml")));
  }

  @Test
  void profileDependenciesAreMigratedWithoutChangingManagedOrPluginScopes() {
    String before =
        """
        <project>
          <dependencyManagement>
            <dependencies>
              <dependency><groupId>pe.axiz</groupId><artifactId>axiz-adapter-cache-redis</artifactId><scope>compile</scope></dependency>
            </dependencies>
          </dependencyManagement>
          <build><plugins><plugin><dependencies>
            <dependency><groupId>pe.axiz</groupId><artifactId>axiz-adapter-cache-redis</artifactId><scope>compile</scope></dependency>
          </dependencies></plugin></plugins></build>
          <profiles><profile><dependencies>
            <dependency><groupId>pe.axiz</groupId><artifactId>axiz-adapter-cache-redis</artifactId><scope>compile</scope></dependency>
          </dependencies></profile></profiles>
        </project>
        """;
    String after =
        before.replace(
            "<profiles><profile><dependencies>\n    <dependency><groupId>pe.axiz</groupId><artifactId>axiz-adapter-cache-redis</artifactId><scope>compile</scope>",
            "<profiles><profile><dependencies>\n    <dependency><groupId>pe.axiz</groupId><artifactId>axiz-adapter-cache-redis</artifactId><scope>runtime</scope>");
    rewriteRun(
        spec -> spec.recipe(new AdaptersAsRuntime()),
        xml(before, after, source -> source.path("pom.xml")));
  }

  @Test
  void otherXmlFilesAreNotTreatedAsProjectDependencies() {
    rewriteRun(
        spec -> spec.recipe(new AdaptersAsRuntime()),
        xml(
            """
        <project><dependencies><dependency><groupId>pe.axiz</groupId><artifactId>axiz-adapter-cache-redis</artifactId></dependency></dependencies></project>
        """,
            source -> source.path("metadata.xml")));
  }
}
