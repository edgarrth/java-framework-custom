package pe.axiz.core.soap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;

class XmlHardeningContractTest {
  @Test
  void effectiveParserPolicyDisablesEachIndependentExternalResourceMechanism() throws Exception {
    // La configuración JAXP efectiva se inspecciona con independencia del rechazo de DOCTYPE: una
    // defensa activa no debe ocultar la eliminación accidental de las demás defensas obligatorias.
    var factoryMethod = SafeXml.class.getDeclaredMethod("factory");
    factoryMethod.setAccessible(true);
    var factory = (DocumentBuilderFactory) factoryMethod.invoke(null);
    assertTrue(factory.isNamespaceAware());
    assertTrue(factory.getFeature(XMLConstants.FEATURE_SECURE_PROCESSING));
    assertTrue(factory.getFeature("http://apache.org/xml/features/disallow-doctype-decl"));
    assertFalse(factory.getFeature("http://xml.org/sax/features/external-general-entities"));
    assertFalse(factory.getFeature("http://xml.org/sax/features/external-parameter-entities"));
    assertFalse(
        factory.getFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd"));
    assertFalse(factory.isXIncludeAware());
    assertFalse(factory.isExpandEntityReferences());
    assertEquals("", factory.getAttribute(XMLConstants.ACCESS_EXTERNAL_DTD));
    assertEquals("", factory.getAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA));
    assertThrows(
        SoapException.class,
        () ->
            SafeXml.parse(
                "<!DOCTYPE x [<!ENTITY external SYSTEM 'file:///axiz-test-do-not-read'>]><x>&external;</x>"));
    assertThrows(
        SoapException.class,
        () -> SafeXml.parse("<!DOCTYPE x [<!ENTITY a 'x'><!ENTITY b '&a;&a;'>]><x>&b;</x>"));
    var include =
        SafeXml.parse(
            "<x xmlns:xi='http://www.w3.org/2001/XInclude'><xi:include href='file:///axiz-test-do-not-read' parse='text'/></x>");
    assertEquals(
        1,
        include.getElementsByTagNameNS("http://www.w3.org/2001/XInclude", "include").getLength());
  }
}
