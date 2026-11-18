package pe.axiz.quality.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import java.util.ArrayList;
import java.util.List;

/**
 * CQRS on top of hexagonal: commands and queries are immutable records; query handlers never depend
 * on command handlers or write ports.
 */
public final class CqrsRules {

  private CqrsRules() {}

  public static List<ArchRule> rules(String basePackage) {
    List<ArchRule> rules = new ArrayList<>(HexagonalRules.rules(basePackage));
    rules.addAll(extensionRules());
    return List.copyOf(rules);
  }

  static List<ArchRule> extensionRules() {
    List<ArchRule> rules = new ArrayList<>();
    rules.add(
        classes()
            .that()
            .implement("pe.axiz.extension.cqrs.Command")
            .should()
            .beRecords()
            .because("commands are immutable"));
    rules.add(
        classes()
            .that()
            .implement("pe.axiz.extension.cqrs.Query")
            .should()
            .beRecords()
            .because("queries are immutable"));
    rules.add(
        noClasses()
            .that()
            .implement("pe.axiz.extension.cqrs.QueryHandler")
            .should()
            .dependOnClassesThat()
            .implement("pe.axiz.extension.cqrs.CommandHandler")
            .because("the read side never triggers writes"));
    rules.add(
        noClasses()
            .that()
            .implement("pe.axiz.extension.cqrs.ReactiveQueryHandler")
            .should()
            .dependOnClassesThat()
            .implement("pe.axiz.extension.cqrs.ReactiveCommandHandler")
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName("pe.axiz.extension.cqrs.spring.ReactiveCommandBus")
            .because("the read side never triggers writes"));
    return List.copyOf(rules);
  }
}
