package pe.axiz.adapter.crypto.jose;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWEObject;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSADecrypter;
import com.nimbusds.jose.crypto.RSAEncrypter;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.crypto.CryptoException;

/**
 * Utilidades JWS y JWE con una lista fija de algoritmos permitidos: PS256, RS256 y ES256 para
 * firmas; RSA-OAEP-256 con A256GCM para cifrado. Se rechazan los tokens que usan cualquier otro
 * algoritmo (incluido {@code none}).
 */
public final class Jose {

  private static final Set<JWSAlgorithm> SIGNATURE_ALGORITHMS =
      Set.of(JWSAlgorithm.PS256, JWSAlgorithm.RS256, JWSAlgorithm.ES256);

  private Jose() {}

  /**
   * Firma un payload.
   *
   * @param payload texto a firmar (normalmente JSON)
   * @param key clave privada RSA o EC
   * @param keyId id de la clave publicado en el JWKS
   * @return JWS compacto
   */
  public static String sign(String payload, PrivateKey key, @Nullable String keyId) {
    try {
      JWSAlgorithm alg = key instanceof ECPrivateKey ? JWSAlgorithm.ES256 : JWSAlgorithm.PS256;
      JWSSigner signer =
          key instanceof ECPrivateKey ec
              ? new ECDSASigner(ec)
              : new RSASSASigner((RSAPrivateKey) key);
      JWSObject jws =
          new JWSObject(new JWSHeader.Builder(alg).keyID(keyId).build(), new Payload(payload));
      jws.sign(signer);
      return jws.serialize();
    } catch (JOSEException | ClassCastException e) {
      throw new CryptoException("JWS signature failed", e);
    }
  }

  /**
   * Verifica un JWS y devuelve su payload, o vacío cuando la firma o el algoritmo no son válidos.
   */
  public static Optional<String> verify(String compact, PublicKey key) {
    try {
      JWSObject jws = JWSObject.parse(compact);
      if (!SIGNATURE_ALGORITHMS.contains(jws.getHeader().getAlgorithm())) {
        return Optional.empty();
      }
      JWSVerifier verifier =
          key instanceof ECPublicKey ec
              ? new ECDSAVerifier(ec)
              : new RSASSAVerifier((RSAPublicKey) key);
      return jws.verify(verifier) ? Optional.of(jws.getPayload().toString()) : Optional.empty();
    } catch (ParseException | JOSEException | ClassCastException e) {
      return Optional.empty();
    }
  }

  /** Cifra un payload para el dueño de la clave pública RSA (RSA-OAEP-256 + A256GCM). */
  public static String encrypt(String payload, RSAPublicKey key, @Nullable String keyId) {
    try {
      JWEObject jwe =
          new JWEObject(
              new JWEHeader.Builder(JWEAlgorithm.RSA_OAEP_256, EncryptionMethod.A256GCM)
                  .keyID(keyId)
                  .build(),
              new Payload(payload));
      jwe.encrypt(new RSAEncrypter(key));
      return jwe.serialize();
    } catch (JOSEException e) {
      throw new CryptoException("JWE encryption failed", e);
    }
  }

  /** Descifra un JWE; solo se acepta RSA-OAEP-256 con A256GCM. */
  public static String decrypt(String compact, RSAPrivateKey key) {
    try {
      JWEObject jwe = JWEObject.parse(compact);
      if (!JWEAlgorithm.RSA_OAEP_256.equals(jwe.getHeader().getAlgorithm())
          || !EncryptionMethod.A256GCM.equals(jwe.getHeader().getEncryptionMethod())) {
        throw new CryptoException("JWE algorithm not allowed");
      }
      jwe.decrypt(new RSADecrypter(key));
      return jwe.getPayload().toString();
    } catch (ParseException | JOSEException e) {
      throw new CryptoException("JWE decryption failed", e);
    }
  }
}
