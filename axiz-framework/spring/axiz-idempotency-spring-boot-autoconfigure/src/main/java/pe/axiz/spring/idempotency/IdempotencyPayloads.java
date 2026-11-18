package pe.axiz.spring.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.concurrent.Stages;
import pe.axiz.core.crypto.AsyncPayloadProtector;
import pe.axiz.core.crypto.CryptoException;
import pe.axiz.core.crypto.PayloadProtector;
import pe.axiz.core.idempotency.StoredResponse;
import pe.axiz.core.json.JsonWriter;

/** Protect once before atomic storage; the deterministic record codec never performs encryption. */
final class IdempotencyPayloads {
  static final String BINDING_ATTRIBUTE = "axiz.idempotency.protectionBinding";

  private IdempotencyPayloads() {}

  record Binding(String context, String row) {
    static Binding of(String operation, String scope, String key) {
      try {
        var hash = MessageDigest.getInstance("SHA-256");
        String framed = JsonWriter.write(List.of(scope, key));
        return new Binding(
            "purpose:idempotency:" + operation,
            HexFormat.of().formatHex(hash.digest(framed.getBytes(StandardCharsets.UTF_8))));
      } catch (NoSuchAlgorithmException missing) {
        throw new IllegalStateException("SHA-256 unavailable", missing);
      }
    }

    Binding response(int status, Map<String, String> headers) {
      try {
        var hash = MessageDigest.getInstance("SHA-256");
        String framed =
            JsonWriter.writeCanonical(Map.of("row", row, "status", status, "headers", headers));
        return new Binding(
            context,
            HexFormat.of().formatHex(hash.digest(framed.getBytes(StandardCharsets.UTF_8))));
      } catch (NoSuchAlgorithmException missing) {
        throw new IllegalStateException("SHA-256 unavailable", missing);
      }
    }

    @Override
    public String toString() {
      return "IdempotencyBinding[****]";
    }
  }

  static StoredResponse store(
      int status,
      Map<String, String> headers,
      byte[] body,
      Binding binding,
      PayloadProtector protector) {
    try {
      byte[] protectedBytes =
          protector.protect(body, binding.context(), binding.response(status, headers).row());
      try {
        return new StoredResponse(status, headers, protectedBytes);
      } finally {
        Arrays.fill(protectedBytes, (byte) 0);
      }
    } finally {
      Arrays.fill(body, (byte) 0);
    }
  }

  static CompletionStage<StoredResponse> store(
      int status,
      Map<String, String> headers,
      byte[] body,
      Binding binding,
      AsyncPayloadProtector protector) {
    return Stages.call(
        () -> {
          try {
            return Stages.consume(
                protector.protect(body, binding.context(), binding.response(status, headers).row()),
                bytes -> new StoredResponse(status, headers, bytes),
                bytes -> Arrays.fill(bytes, (byte) 0),
                ignored -> {});
          } finally {
            Arrays.fill(body, (byte) 0);
          }
        });
  }

  static StoredResponse reveal(
      StoredResponse response, Binding binding, PayloadProtector protector) {
    byte[] stored = response.body();
    try {
      byte[] body =
          protector.reveal(
              stored,
              binding.context(),
              binding.response(response.status(), response.headers()).row());
      try {
        if (response.bodyOmitted()) omitted(body);
        return new StoredResponse(response.status(), response.headers(), body);
      } finally {
        Arrays.fill(body, (byte) 0);
      }
    } finally {
      Arrays.fill(stored, (byte) 0);
    }
  }

  static CompletionStage<StoredResponse> reveal(
      StoredResponse response, Binding binding, AsyncPayloadProtector protector) {
    return Stages.call(
        () -> {
          byte[] stored = response.body();
          try {
            return Stages.consume(
                protector.reveal(
                    stored,
                    binding.context(),
                    binding.response(response.status(), response.headers()).row()),
                body -> {
                  if (response.bodyOmitted()) omitted(body);
                  return new StoredResponse(response.status(), response.headers(), body);
                },
                bytes -> Arrays.fill(bytes, (byte) 0),
                ignored -> {});
          } finally {
            Arrays.fill(stored, (byte) 0);
          }
        });
  }

  private static void omitted(byte[] stored) {
    if (stored.length != 0)
      throw new CryptoException("Omitted response unexpectedly contains a body");
  }
}
