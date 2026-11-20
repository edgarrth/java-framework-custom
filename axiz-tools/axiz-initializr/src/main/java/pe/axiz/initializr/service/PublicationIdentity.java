package pe.axiz.initializr.service;

import jakarta.annotation.PreDestroy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pe.axiz.codegen.output.GeneratedProject;

/** Authenticated operation marker; the same protected deployment key is used by every replica. */
@Component
public final class PublicationIdentity {
  private final byte[] key;

  public PublicationIdentity(@Value("${axiz.initializr.publication-key:}") String encoded) {
    try {
      key = encoded.isBlank() ? new byte[0] : Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("Invalid publication signing key");
    }
    if (key.length < 32)
      throw new IllegalArgumentException("Publication signing key requires at least 256 bits");
  }

  public String marker(
      GeneratedProject project, UUID operation, String principal, String repository) {
    if (key.length == 0)
      throw new IllegalStateException("GitHub publication signing key is not configured");
    String digest = digest(project);
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      for (String field :
          new String[] {"axiz-init-v1", operation.toString(), principal, repository, digest}) {
        byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
        mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        mac.update(bytes);
      }
      return "[axiz-init-v1:"
          + operation
          + ":"
          + digest
          + ":"
          + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal())
          + "]";
    } catch (GeneralSecurityException unavailable) {
      throw new IllegalStateException("Publication identity unavailable", unavailable);
    }
  }

  private static String digest(GeneratedProject project) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (Map.Entry<String, byte[]> entry : new TreeMap<>(project.files()).entrySet()) {
        byte[] path = entry.getKey().getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(path.length).array());
        digest.update(path);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(entry.getValue().length).array());
        digest.update(entry.getValue());
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (GeneralSecurityException unavailable) {
      throw new IllegalStateException("Publication digest unavailable", unavailable);
    }
  }

  @PreDestroy
  public void close() {
    Arrays.fill(key, (byte) 0);
  }
}
