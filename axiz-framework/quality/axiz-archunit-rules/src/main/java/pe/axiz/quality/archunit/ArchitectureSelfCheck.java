package pe.axiz.quality.archunit;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import pe.axiz.quality.archunit.selfcheck.application.UnsafeMoney;

/**
 * Confirma que la colección real de reglas del servicio rechaza un fixture prohibido conocido.
 * Detecta conjuntos de reglas vacíos o desconectados; no prueba cada regla de arquitectura
 * individual.
 */
public final class ArchitectureSelfCheck {
  private ArchitectureSelfCheck() {}

  public static void verify(AxizArchitecture architecture) {
    verify(architecture.rules());
  }

  public static void verify(List<ArchRule> rules) {
    var fixture = new ClassFileImporter().importClasses(UnsafeMoney.class);
    boolean rejected =
        rules.stream()
            .anyMatch(rule -> rule.allowEmptyShould(true).evaluate(fixture).hasViolation());
    if (!rejected)
      throw new AssertionError(
          "Architecture rules did not reject the prohibited self-check fixture");
  }
}
