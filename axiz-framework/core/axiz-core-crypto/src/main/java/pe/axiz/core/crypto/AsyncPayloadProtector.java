package pe.axiz.core.crypto;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.concurrent.Stages;

/**
 * Protección técnica de almacenamiento asíncrona nativa; sin executor para operaciones de clave
 * bloqueantes. Las implementaciones toman un snapshot de los arrays de entrada antes de retornar,
 * porque quienes llaman pueden limpiarlos de inmediato. Los arrays devueltos pertenecen al
 * receptor; la cancelación debe limpiar los resultados no entregados.
 */
public interface AsyncPayloadProtector {
  CompletionStage<byte[]> protect(byte[] plaintext, String context, String binding);

  CompletionStage<byte[]> reveal(byte[] stored, String context, String binding);

  default CompletionStage<String> protect(String plaintext, String context, String binding) {
    return Stages.call(
        () -> {
          byte[] bytes = PayloadProtection.utf8(plaintext);
          try {
            return Stages.consume(
                protect(bytes, context, binding),
                PayloadProtection::text,
                value -> Arrays.fill(value, (byte) 0),
                ignored -> {});
          } finally {
            Arrays.fill(bytes, (byte) 0);
          }
        });
  }

  default CompletionStage<String> reveal(String stored, String context, String binding) {
    return Stages.call(
        () -> {
          byte[] input = PayloadProtection.parse(stored);
          try {
            return Stages.consume(
                reveal(input, context, binding),
                PayloadProtection::utf8,
                bytes -> Arrays.fill(bytes, (byte) 0),
                ignored -> {});
          } finally {
            Arrays.fill(input, (byte) 0);
          }
        });
  }

  /**
   * Modo identidad solo de CPU, seleccionado de forma explícita. Los registros protegidos siempre
   * fallan en cerrado.
   */
  AsyncPayloadProtector NONE =
      new AsyncPayloadProtector() {
        @Override
        public CompletionStage<byte[]> protect(byte[] value, String context, String binding) {
          return Stages.call(
              () ->
                  CompletableFuture.completedFuture(
                      PayloadProtector.NONE.protect(value, context, binding)));
        }

        @Override
        public CompletionStage<byte[]> reveal(byte[] value, String context, String binding) {
          return Stages.call(
              () ->
                  CompletableFuture.completedFuture(
                      PayloadProtector.NONE.reveal(value, context, binding)));
        }

        @Override
        public CompletionStage<String> protect(String value, String context, String binding) {
          return Stages.call(
              () ->
                  CompletableFuture.completedFuture(
                      PayloadProtector.NONE.protect(value, context, binding)));
        }

        @Override
        public CompletionStage<String> reveal(String value, String context, String binding) {
          return Stages.call(
              () ->
                  CompletableFuture.completedFuture(
                      PayloadProtector.NONE.reveal(value, context, binding)));
        }
      };
}
