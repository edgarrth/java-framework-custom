package pe.axiz.quality.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.lang.ArchRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Client applications cannot acquire the engine, storage SPI or database/budget integrations. */
public final class TokenizationRules {
  private TokenizationRules() {}

  public static List<ArchRule> rules(String base, TokenizationScope scope) {
    var rules = new ArrayList<ArchRule>();
    if (scope != TokenizationScope.VAULT)
      rules.add(
          noClasses()
              .that()
              .resideInAPackage(base + "..")
              .should()
              .dependOnClassesThat()
              .resideInAnyPackage(
                  "pe.axiz.core.tokenization.vault..",
                  "pe.axiz.adapter.tokenization..",
                  "pe.axiz.spring.tokenization.vault.."));
    if (scope == TokenizationScope.NONE)
      rules.add(
          noClasses()
              .that()
              .resideInAPackage(base + "..")
              .should()
              .dependOnClassesThat(
                  DescribedPredicate.describe(
                      "tokenization operations",
                      type ->
                          Set.of(
                                      "pe.axiz.core.tokenization.Tokenizer",
                                      "pe.axiz.core.tokenization.AsyncTokenizer")
                                  .contains(type.getName())
                              || type.getPackageName().startsWith("pe.axiz.spring.tokenization"))));
    return List.copyOf(rules);
  }
}
