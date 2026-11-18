package pe.axiz.quality.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import pe.axiz.core.sensitive.SensitiveKeys;

/** Typed card fields and controlled reveal sites. Raw Strings still require runtime detection. */
public final class CardDataRules {
  private static final SensitiveKeys KEYS = SensitiveKeys.defaults();
  private static final Set<String> UNSUPPORTED =
      Set.of("pinblock", "track1", "track2", "trackdata");
  private static final Set<String> CARD =
      Set.of(
          "pan",
          "cardnumber",
          "primaryaccountnumber",
          "numerotarjeta",
          "cvv",
          "cvv2",
          "cvc",
          "cvc2",
          "cid",
          "securitycode",
          "cardverificationvalue",
          "pin");
  private static final Set<String> SEMANTIC =
      Set.of(
          "pe.axiz.core.carddata.Pan",
          "pe.axiz.core.carddata.TruncatedPan",
          "pe.axiz.core.carddata.CardVerificationCode",
          "pe.axiz.core.carddata.Pin",
          "pe.axiz.core.tokenization.Token",
          "pe.axiz.core.crypto.EncryptedValue",
          "pe.axiz.core.crypto.KeyedHash");

  private CardDataRules() {}

  public static List<ArchRule> rules(String base, CardDataScope scope) {
    var rules = new ArrayList<ArchRule>();
    if (scope == CardDataScope.NONE)
      rules.add(
          noClasses()
              .that()
              .resideInAPackage(base + "..")
              .should()
              .dependOnClassesThat()
              .resideInAPackage("pe.axiz.core.carddata.."));
    rules.add(
        fields()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage(base + "..")
            .should(
                new ArchCondition<>(
                    "use semantic card types and never model unsupported authentication data") {
                  @Override
                  public void check(JavaField field, ConditionEvents events) {
                    KEYS.find(field.getName())
                        .ifPresent(
                            key -> {
                              boolean invalid =
                                  UNSUPPORTED.contains(key.name())
                                      || (CARD.contains(key.name())
                                          && field.getType().getAllInvolvedRawTypes().stream()
                                              .noneMatch(
                                                  type ->
                                                      SEMANTIC.contains(
                                                          type.getBaseComponentType().getName())));
                              if (invalid)
                                events.add(
                                    SimpleConditionEvent.violated(
                                        field,
                                        field.getFullName()
                                            + " must use a supported semantic card type"));
                            });
                  }
                }));
    rules.add(
        noClasses()
            .that()
            .resideInAPackage(base + "..")
            .and()
            .resideOutsideOfPackages(base + ".adapter.out..", base + ".client..")
            .should()
            .callMethodWhere(
                DescribedPredicate.describe(
                    "card secret reveal",
                    call ->
                        (call.getTargetOwner().getName().equals("pe.axiz.core.carddata.Pan")
                                && call.getName().equals("reveal"))
                            || (Set.of(
                                        "pe.axiz.core.carddata.CardVerificationCode",
                                        "pe.axiz.core.carddata.Pin")
                                    .contains(call.getTargetOwner().getName())
                                && call.getName().equals("use")))));
    rules.add(
        noClasses()
            .that()
            .resideInAPackage(base + "..")
            .and()
            .resideOutsideOfPackages(base + ".adapter.out..", base + ".application..")
            .should()
            .callMethodWhere(
                DescribedPredicate.describe(
                    "token reveal",
                    call ->
                        call.getName().equals("detokenize")
                            && (call.getTargetOwner()
                                    .isAssignableTo("pe.axiz.core.tokenization.Tokenizer")
                                || call.getTargetOwner()
                                    .isAssignableTo("pe.axiz.core.tokenization.AsyncTokenizer")
                                || call.getTargetOwner()
                                    .getName()
                                    .equals("pe.axiz.spring.tokenization.ReactiveTokenizer")))));
    rules.add(
        classes().that().resideInAPackage(base + "..").should(new StoredDataPolicy(base, scope)));
    return List.copyOf(rules);
  }
}
