package pe.axiz.adapter.crypto.jose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pe.axiz.core.crypto.CryptoException;
import pe.axiz.core.crypto.Rsa;

class JoseTest {

  @Test
  void rsaAndEcSignatures() throws Exception {
    KeyPair rsa = Rsa.newKeyPair(2048);
    String jws = Jose.sign("{\"a\":1}", rsa.getPrivate(), "k1");
    assertEquals(Optional.of("{\"a\":1}"), Jose.verify(jws, rsa.getPublic()));
    assertTrue(Jose.verify(jws, Rsa.newKeyPair(2048).getPublic()).isEmpty());
    KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
    g.initialize(new ECGenParameterSpec("secp256r1"));
    KeyPair ec = g.generateKeyPair();
    String ecJws = Jose.sign("x", ec.getPrivate(), null);
    assertEquals(Optional.of("x"), Jose.verify(ecJws, ec.getPublic()));
    assertTrue(Jose.verify("eyJhbGciOiJub25lIn0.eyJhIjoxfQ.", rsa.getPublic()).isEmpty());
    assertTrue(Jose.verify("garbage", rsa.getPublic()).isEmpty());
  }

  @Test
  void jweRoundTrip() {
    KeyPair rsa = Rsa.newKeyPair(2048);
    String jwe = Jose.encrypt("{\"pan\":\"4111\"}", (RSAPublicKey) rsa.getPublic(), "enc-1");
    assertEquals("{\"pan\":\"4111\"}", Jose.decrypt(jwe, (RSAPrivateKey) rsa.getPrivate()));
    assertThrows(
        CryptoException.class,
        () -> Jose.decrypt(jwe, (RSAPrivateKey) Rsa.newKeyPair(2048).getPrivate()));
    assertThrows(
        CryptoException.class, () -> Jose.decrypt("bad", (RSAPrivateKey) rsa.getPrivate()));
  }
}
