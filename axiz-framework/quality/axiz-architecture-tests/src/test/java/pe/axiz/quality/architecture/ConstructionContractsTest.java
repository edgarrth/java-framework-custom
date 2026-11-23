package pe.axiz.quality.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.constructors;

import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

/**
 * La construcción con nombre es el contrato público; los constructores canónicos de los records
 * siguen siendo contratos de la JVM.
 */
class ConstructionContractsTest {
  @Test
  void extensivePublicConstructionUsesBuilders() {
    var classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("pe.axiz");
    constructors()
        .that()
        .arePublic()
        .or()
        .areProtected()
        .should(
            new ArchCondition<JavaConstructor>("use a builder above seven parameters") {
              @Override
              public void check(JavaConstructor constructor, ConditionEvents events) {
                if (constructor.getRawParameterTypes().size() <= 7) return;
                var owner = constructor.getOwner();
                boolean record =
                    owner
                        .getRawSuperclass()
                        .map(parent -> parent.getName().equals("java.lang.Record"))
                        .orElse(false);
                boolean builder =
                    owner.getMethods().stream()
                        .anyMatch(method -> method.getName().equals("builder"));
                events.add(
                    new SimpleConditionEvent(
                        constructor,
                        record && builder,
                        constructor.getFullName() + " must expose named construction"));
              }
            })
        .check(classes);
  }
}
