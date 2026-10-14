package pe.axiz.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NamesTest {

  @Test
  void convertsBetweenConventions() {
    assertEquals("CreatePayment", Names.pascal("create-payment"));
    assertEquals("CreatePayment", Names.pascal("createPayment"));
    assertEquals("createPayment", Names.camel("create_payment"));
    assertEquals("create-payment", Names.kebab("CreatePayment"));
    assertEquals("create_payment", Names.snake("create-payment"));
    assertEquals("pe/axiz/orders", Names.packagePath("pe.axiz.orders"));
  }

  @Test
  void identifiersAvoidKeywordsAndReservedNames() {
    assertEquals("classValue", Names.identifier("class"));
    assertEquals("bodyValue", Names.identifier("body"));
    assertEquals("pageValue", Names.identifier("page"));
    assertEquals("orderId", Names.identifier("order-id"));
    assertEquals("p1st", Names.identifier("1st"));
  }

  @Test
  void validatesArtifactsAndPackages() {
    assertTrue(Names.isArtifactId("payments-business"));
    assertFalse(Names.isArtifactId("Payments"));
    assertFalse(Names.isArtifactId("../etc"));
    assertTrue(Names.isPackage("pe.axiz.payments"));
    assertFalse(Names.isPackage("pe.axiz.Payments"));
    assertFalse(Names.isPackage("pe..axiz"));
  }

  @Test
  void errorPrefixesFollowTheCodeFormat() {
    assertEquals(
        "PAYMENTS-PAYMENTSBUSINESS", Names.errorPrefix("pe.axiz.payments", "payments-business"));
    assertEquals("APP1X-AB", Names.errorPrefix("pe.1x", "ab"));
    assertTrue(
        Names.errorPrefix("pe.axiz.verylonggroupsegment", "a-very-long-service-name-indeed")
            .matches("^[A-Z][A-Z0-9]{1,9}-[A-Z0-9]{2,20}$"));
  }
}
