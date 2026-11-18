package pe.axiz.quality.archunit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.quality.archunit.sample.sensitive.model.DataShapes;

class SensitiveArchitectureTest {
  private static final String BASE = "pe.axiz.quality.archunit.sample.sensitive";

  private static boolean fails(CardDataScope scope, Class<?>... types) {
    var classes = new ClassFileImporter().importClasses(types);
    return CardDataRules.rules(BASE, scope).stream()
        .anyMatch(rule -> rule.allowEmptyShould(true).evaluate(classes).hasViolation());
  }

  @Test
  void cardNamesRequireSemanticTypesAndUnsupportedDataCannotBeModeled() {
    for (Class<?> bad :
        List.of(DataShapes.RawPan.class, DataShapes.NumericPan.class, DataShapes.Unsupported.class))
      assertTrue(fails(CardDataScope.EDGE, bad), bad.getName());
    for (Class<?> good :
        List.of(DataShapes.TypedPan.class, DataShapes.Truncated.class, DataShapes.CardToken.class))
      assertFalse(fails(CardDataScope.EDGE, good), good.getName());
    assertTrue(fails(CardDataScope.NONE, DataShapes.TypedPan.class));
  }

  @Test
  void sadCannotBeHiddenInContainersEmbeddedTypesOrSagaContexts() {
    for (Class<?> bad :
        List.of(
            DataShapes.DirectSad.class,
            DataShapes.ListSad.class,
            DataShapes.OptionalSad.class,
            DataShapes.ArraySad.class,
            DataShapes.EmbeddedSad.class))
      assertTrue(fails(CardDataScope.VAULT, bad, DataShapes.Nested.class), bad.getName());
    assertTrue(fails(CardDataScope.VAULT, DataShapes.SagaContext.class, DataShapes.SagaUse.class));
  }

  @Test
  void onlyEffectiveMappingsAndTypedProtectedValuesQualify() {
    assertTrue(fails(CardDataScope.NONE, DataShapes.Computed.class));
    assertFalse(
        fails(CardDataScope.NONE, DataShapes.ComputedProtected.class, DataShapes.Encryption.class));
    assertTrue(
        fails(
            CardDataScope.NONE,
            DataShapes.Deep.class,
            DataShapes.Level1.class,
            DataShapes.Level2.class,
            DataShapes.Level3.class,
            DataShapes.Level4.class,
            DataShapes.Level5.class));
    for (Class<?> good :
        List.of(
            DataShapes.FieldMapped.class, DataShapes.GetterMapped.class, DataShapes.Explicit.class))
      assertFalse(fails(CardDataScope.NONE, good, DataShapes.Encryption.class), good.getName());
    for (Class<?> bad :
        List.of(DataShapes.WrongSide.class, DataShapes.Unprotected.class, DataShapes.Deep.class))
      assertTrue(fails(CardDataScope.NONE, bad), bad.getName());
    assertFalse(
        fails(CardDataScope.NONE, DataShapes.AutoMapped.class, DataShapes.AutoEncryption.class));
    assertTrue(
        fails(CardDataScope.NONE, DataShapes.Disabled.class, DataShapes.AutoEncryption.class));
  }

  @Test
  void tokenClientsAndFieldProtectionDoNotAcquireTheVault() {
    var importer = new ClassFileImporter();
    for (TokenizationScope scope : List.of(TokenizationScope.NONE, TokenizationScope.CLIENT))
      assertTrue(
          TokenizationRules.rules(BASE, scope).stream()
              .anyMatch(
                  rule ->
                      rule.allowEmptyShould(true)
                          .evaluate(importer.importClasses(DataShapes.StolenVault.class))
                          .hasViolation()));
    assertFalse(
        TokenizationRules.rules(BASE, TokenizationScope.CLIENT).stream()
            .anyMatch(
                rule ->
                    rule.allowEmptyShould(true)
                        .evaluate(importer.importClasses(DataShapes.Client.class))
                        .hasViolation()));
    assertFalse(
        TokenizationRules.rules(BASE, TokenizationScope.NONE).stream()
            .anyMatch(
                rule ->
                    rule.allowEmptyShould(true)
                        .evaluate(importer.importClasses(DataShapes.SafeFields.class))
                        .hasViolation()));
  }
}
