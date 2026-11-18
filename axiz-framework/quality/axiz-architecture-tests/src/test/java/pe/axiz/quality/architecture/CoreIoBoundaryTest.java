package pe.axiz.quality.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class CoreIoBoundaryTest {
  @Test
  void everyCoreModuleDelegatesIo() {
    FrameworkArchitectureTest.CORE_DOES_NOT_PERFORM_IO.check(
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("pe.axiz.core"));
  }
}
