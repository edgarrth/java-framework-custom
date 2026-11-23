package pe.axiz.core.sequence;

import java.util.concurrent.CompletionStage;

/**
 * Asignación atómica de secuencias asíncrona nativa. Un extremo exitoso ya debe ser durable, con
 * independencia de la transacción de negocio de quien llama. Las reservas canceladas pueden dejar
 * huecos.
 */
@FunctionalInterface
public interface AsyncSequenceStore {
  CompletionStage<Long> increment(String name, String period, int increment);
}
