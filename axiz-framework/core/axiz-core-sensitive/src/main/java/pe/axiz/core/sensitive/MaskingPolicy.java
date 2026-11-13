package pe.axiz.core.sensitive;

import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Decides how a sensitive value is written to each output.
 *
 * <p>Default policy: CRITICAL values are always removed (full mask); HIGH values are masked in
 * every output; MEDIUM values are masked in logs and audit and shown masked in responses; LOW
 * values are shown in responses and audit and masked in logs.
 *
 * @param minimumLevelToMask per output, the lowest level that gets masked
 * @param fullMaskLevel from this level on, values are always fully masked
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
   * Whether a value of this level is masked on the channel (fully or partially).
   *
   * @param level sensitivity of the value
   * @param channel where the value goes
   * @return true when {@link #apply} changes a value of this level
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
