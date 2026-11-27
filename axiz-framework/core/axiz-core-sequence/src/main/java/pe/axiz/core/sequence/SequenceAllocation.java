package pe.axiz.core.sequence;

import java.util.Objects;
import java.util.regex.Pattern;

/** Invariantes de asignación compartidas por los generadores síncronos y asíncronos nativos. */
final class SequenceAllocation {
  private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_-]{0,62}$");

  private SequenceAllocation() {}

  static void validate(String name, int blockSize) {
    if (!NAME.matcher(Objects.requireNonNull(name)).matches())
      throw new IllegalArgumentException("Invalid sequence name " + name);
    if (blockSize < 1 || blockSize > 10_000)
      throw new IllegalArgumentException("blockSize must be between 1 and 10000");
  }

  static long first(long last, int blockSize) {
    if (last < blockSize) throw new IllegalStateException("Invalid sequence reservation endpoint");
    return last - blockSize + 1;
  }
}
