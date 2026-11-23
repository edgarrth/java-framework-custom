package pe.axiz.core.identifiers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pe.axiz.core.identifiers.banking.BankingIdentifiers.CardBrand;
import pe.axiz.core.identifiers.banking.CardSchemeResolver;
import pe.axiz.core.identifiers.banking.IinRangeTable;

/**
 * La marca de una tarjeta proviene de una tabla gobernada: primero el rango más específico, datos y
 * no código.
 */
class IinRangeTableTest {

  private static final String TABLE =
      """
      # sample table: illustrative ranges, not real assignments
      4,4,VISA
      51,55,MASTERCARD
      2221,2720,MASTERCARD
      36,36,DINERS
      # a co-branded range inside a wider range of another scheme
      455500,455599,DISCOVER
      45550012,45550012,JCB
      """;

  private final CardSchemeResolver table = IinRangeTable.parse(new StringReader(TABLE), "test 1");

  @Test
  void theMostSpecificRangeWins() {
    assertEquals(Optional.of(CardBrand.VISA), table.resolve("4111111111111111"));
    assertEquals(Optional.of(CardBrand.DISCOVER), table.resolve("4555001111111111"));
    assertEquals(Optional.of(CardBrand.JCB), table.resolve("4555001299999999"));
    assertEquals(Optional.of(CardBrand.MASTERCARD), table.resolve("2720999999999999"));
    assertEquals(Optional.of(CardBrand.DINERS), table.resolve("36000000000008"));
    assertEquals("test 1", table.source());
    assertEquals(6, ((IinRangeTable) table).size());
  }

  @Test
  void whatTheTableDoesNotKnowIsNotGuessed() {
    assertEquals(Optional.empty(), table.resolve("6011111111111117"));
    assertEquals(Optional.empty(), table.resolve("2"), "shorter than every matching prefix");
    assertEquals(Optional.empty(), table.resolve(""));
    assertEquals(Optional.empty(), table.resolve("41x1"));
  }

  @Test
  void aMalformedTableIsRejectedAsAWhole() {
    String[] bad = {
      "4,VISA",
      "4,44,VISA",
      "5a,59,MASTERCARD",
      "59,51,MASTERCARD",
      "4,4,PRIVATE",
      "4,4,UNKNOWN",
      "123456789012,123456789012,VISA",
      ",,VISA",
    };
    for (String line : bad) {
      IllegalArgumentException e =
          assertThrows(
              IllegalArgumentException.class,
              () -> IinRangeTable.parse(new StringReader("4,4,VISA\n" + line), "t"),
              line);
      assertTrue(e.getMessage().contains("line 2"), e.getMessage());
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> IinRangeTable.parse(new StringReader("# none\n"), "t"));
    assertThrows(
        IllegalArgumentException.class,
        () -> IinRangeTable.parse(new StringReader("4,4,VISA"), " "));
  }

  @Test
  void theHeuristicIsOnlyAGuess() {
    // el mismo PAN que la tabla asigna a un rango co-branded es simplemente "VISA" para la
    // heurística
    assertEquals(CardBrand.VISA, CardBrand.guess("4555001299999999"));
    assertEquals(Optional.of(CardBrand.JCB), table.resolve("4555001299999999"));
  }
}
