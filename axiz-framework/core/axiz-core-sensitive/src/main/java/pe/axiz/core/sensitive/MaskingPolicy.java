package pe.axiz.core.sensitive;

import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Decide cómo se escribe un valor sensible en cada salida.
 *
 * <p>Política por defecto: los valores CRITICAL siempre se eliminan (máscara completa); los valores
 * HIGH se enmascaran en todas las salidas; los valores MEDIUM se enmascaran en logs y auditoría y
 * se muestran enmascarados en las respuestas; los valores LOW se muestran en respuestas y auditoría
 * y se enmascaran en logs.
 *
 * @param minimumLevelToMask por salida, el nivel más bajo que se enmascara
 * @param fullMaskLevel a partir de este nivel, los valores siempre se enmascaran por completo
 */
public record MaskingPolicy(
    Map<OutputChannel, SensitivityLevel> minimumLevelToMask, SensitivityLevel fullMaskLevel) {

  public MaskingPolicy {
    minimumLevelToMask = Map.copyOf(minimumLevelToMask);
    Objects.requireNonNull(fullMaskLevel);
  }

  public static MaskingPolicy defaults() {
    return new MaskingPolicy(
        Map.of(
            OutputChannel.RESPONSE, SensitivityLevel.MEDIUM,
            OutputChannel.LOG, SensitivityLevel.LOW,
            OutputChannel.AUDIT, SensitivityLevel.MEDIUM),
        SensitivityLevel.CRITICAL);
  }

  /**
   * Indica si un valor de este nivel se enmascara en el canal (por completo o en parte).
   *
   * @param level sensibilidad del valor
   * @param channel destino del valor
   * @return true cuando {@link #apply} cambia un valor de este nivel
   */
  public boolean masks(SensitivityLevel level, OutputChannel channel) {
    return level.compareTo(fullMaskLevel) >= 0
        || level.compareTo(minimumLevelToMask.getOrDefault(channel, SensitivityLevel.LOW)) >= 0;
  }

  public @Nullable String apply(
      @Nullable String value, SensitivityLevel level, MaskType mask, OutputChannel channel) {
    if (value == null) {
      return null;
    }
    if (level.compareTo(fullMaskLevel) >= 0) {
      return Masker.FULL_MASK;
    }
    SensitivityLevel threshold = minimumLevelToMask.getOrDefault(channel, SensitivityLevel.LOW);
    if (level.compareTo(threshold) < 0) {
      return value;
    }
    return Masker.mask(value, mask == MaskType.NONE ? MaskType.PARTIAL : mask);
  }
}
