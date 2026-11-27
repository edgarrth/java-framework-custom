package pe.axiz.demo.coverage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.SAXException;

class ExecutionEvidenceTest {
  @TempDir Path directory;

  private Path report(String outcome) throws Exception {
    Files.writeString(directory.resolve("pom.xml"), "<project/>");
    Files.createDirectories(directory.resolve("src/test/java"));
    Files.writeString(directory.resolve("src/test/java/Recipe.java"), "// fixture");
    Path reports = directory.resolve("target/surefire-reports");
    Files.createDirectories(reports);
    Path report = reports.resolve("TEST-example.RecipeTest.xml");
    Files.writeString(
        report,
        "<testsuite><testcase classname=\"example.RecipeTest\" name=\"roundTrip\">"
            + outcome
            + "</testcase></testsuite>");
    return report;
  }

  @Test
  void aFreshSuccessfulOperationIsAccepted() throws Exception {
    report("");
    assertDoesNotThrow(
        () -> CapabilityCoverageTest.validateExecution(directory, "example.RecipeTest#roundTrip"));
  }

  @Test
  void skippedFailedAndMissingOperationsCannotClaimCapabilityCoverage() throws Exception {
    for (String rejected : new String[] {"skipped", "failure", "error"}) {
      report("<" + rejected + "/>");
      assertThrows(
          AssertionError.class,
          () ->
              CapabilityCoverageTest.validateExecution(directory, "example.RecipeTest#roundTrip"));
    }
    report("");
    assertThrows(
        AssertionError.class,
        () -> CapabilityCoverageTest.validateExecution(directory, "example.RecipeTest#neverRun"));
    assertThrows(
        AssertionError.class,
        () -> CapabilityCoverageTest.validateExecution(directory, "example.MissingTest#roundTrip"));
  }

  @Test
  void anOldReportCannotProveNewSourceBehavior() throws Exception {
    var report = report("");
    Files.setLastModifiedTime(report, FileTime.fromMillis(1));
    assertThrows(
        AssertionError.class,
        () -> CapabilityCoverageTest.validateExecution(directory, "example.RecipeTest#roundTrip"));
  }

  @Test
  void pluginReportsAreDistinctAndAnOverwrittenEventReportCannotProveAnotherOperation()
      throws Exception {
    var report = report("");
    eventReport("SUCCESSFUL");
    Path events = report.resolveSibling("open-test-report.xml");
    Files.writeString(
        events, Files.readString(events).replace("example.RecipeTest", "example.OtherIT"));
    assertThrows(
        AssertionError.class,
        () -> OpenTestEvidence.variants(events, "example.RecipeTest", "roundTrip", "sequence"));
    Path failsafe = directory.resolve("target/failsafe-reports");
    Files.createDirectories(failsafe);
    Files.move(report, failsafe.resolve(report.getFileName()));
    assertDoesNotThrow(
        () -> CapabilityCoverageTest.validateExecution(directory, "example.RecipeTest#roundTrip"));
  }

  private void eventReport(String outcome) throws Exception {
    Files.writeString(
        directory.resolve("target/surefire-reports/open-test-report.xml"),
        """
        <e:events xmlns="https://schemas.opentest4j.org/reporting/core/0.2.0"
          xmlns:e="https://schemas.opentest4j.org/reporting/events/0.2.0"
          xmlns:java="https://schemas.opentest4j.org/reporting/java/0.2.0">
          <e:started id="1"><sources>
            <java:methodSource className="example.RecipeTest" methodName="roundTrip"/>
          </sources></e:started>
          <e:reported id="1"><attachments><data>
            <entry key="axiz.capability.sequence">aurora</entry>
          </data></attachments></e:reported>
          <e:finished id="1"><result status="OUTCOME"/></e:finished>
        </e:events>
        """
            .replace("OUTCOME", outcome));
  }

  @Test
  void aYamlVariantCannotInventAnOperationAndMissingProviderCannotPass() throws Exception {
    var report = report("");
    var jar = directory.resolve("pe/axiz/axiz-fixture/2.0.0/axiz-fixture-2.0.0.jar");
    Files.createDirectories(Objects.requireNonNull(jar.getParent()));
    Files.write(jar, new byte[] {1});
    Files.writeString(
        report,
        "<testsuite><properties><property name=\"java.class.path\" value=\""
            + jar
            + "\"/></properties><testcase classname=\"example.RecipeTest\" name=\"roundTrip\"/></testsuite>");
    eventReport("SUCCESSFUL");
    var item = CapabilityCoverageTest.validateExecution(directory, "example.RecipeTest#roundTrip");
    assertDoesNotThrow(
        () ->
            RuntimeEvidence.validate(
                item, "sequence", List.of("aurora"), Map.of("starter", "axiz-fixture")));
    var variant =
        assertThrows(
            AssertionError.class,
            () ->
                RuntimeEvidence.validate(
                    item, "sequence", List.of("r2dbc"), Map.of("starter", "axiz-fixture")));
    assertTrue(variant.getMessage().contains("did not demonstrate"));
    var provider =
        assertThrows(
            AssertionError.class,
            () ->
                RuntimeEvidence.validate(
                    item,
                    "sequence",
                    List.of("aurora"),
                    Map.of(
                        "starter",
                        "axiz-fixture",
                        "persistence",
                        Map.of(
                            "aurora",
                            Map.of("dependencies", List.of("pe.axiz:missing-provider"))))));
    assertTrue(provider.getMessage().contains("missing-provider"));
    Path events = report.resolveSibling("open-test-report.xml");
    Files.setLastModifiedTime(events, FileTime.fromMillis(1));
    var staleEvents =
        assertThrows(
            AssertionError.class,
            () ->
                RuntimeEvidence.validate(
                    item, "sequence", List.of("aurora"), Map.of("starter", "axiz-fixture")));
    assertTrue(staleEvents.getMessage().contains("Stale execution"));
    eventReport("SUCCESSFUL");
    Files.setLastModifiedTime(
        jar, FileTime.fromMillis(Files.getLastModifiedTime(report).toMillis() + 1000));
    assertThrows(
        AssertionError.class, () -> RuntimeEvidence.validateFreshness(List.of(jar), report));
    eventReport("FAILED");
    assertThrows(
        AssertionError.class,
        () ->
            OpenTestEvidence.variants(
                report.resolveSibling("open-test-report.xml"),
                "example.RecipeTest",
                "roundTrip",
                "sequence"));
  }

  @Test
  void testReportCannotExpandExternalEntities() throws Exception {
    var report = report("");
    Files.writeString(
        report,
        "<!DOCTYPE testsuite [<!ENTITY external SYSTEM 'file:///nonexistent'>]>"
            + "<testsuite>&external;</testsuite>");
    assertThrows(
        SAXException.class,
        () -> CapabilityCoverageTest.validateExecution(directory, "example.RecipeTest#roundTrip"));
  }
}
