package pe.axiz.core.identifiers.banking;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import pe.axiz.core.identifiers.CheckDigits;
import pe.axiz.core.identifiers.banking.BankingIdentifiers.CardBrand;

/**
 * {@link CardSchemeResolver} sobre una tabla de rangos IIN/BIN cargada desde datos (un archivo, un
 * objeto en S3, un parámetro): los rangos se mantienen fuera del código y la tabla lleva su propio
 * origen y versión, de modo que una actualización es una entrega de datos y no un despliegue.
 *
 * <p>Formato: un rango por línea, {@code <low>,<high>,<scheme>}, donde {@code low} y {@code high}
 * son prefijos de la misma longitud (1 a 11 dígitos) y {@code scheme} es un nombre de {@link
 * CardBrand}. Se ignoran las líneas vacías y las que empiezan con {@code #}. Cuando los rangos se
 * superponen, prevalece el más específico (el prefijo más largo y luego el rango más estrecho), que
 * es como se representan los rangos co-branded y privados dentro del rango de una marca. Una línea
 * mal formada rechaza la tabla completa.
 */
public final class IinRangeTable implements CardSchemeResolver {

  private static final int MAX_PREFIX = 11;

  private record Range(String low, String high, CardBrand brand) {
    boolean contains(String pan) {
      if (pan.length() < low.length()) {
        return false;
      }
      String prefix = pan.substring(0, low.length());
      return prefix.compareTo(low) >= 0 && prefix.compareTo(high) <= 0;
    }

    long width() {
      return Long.parseLong(high) - Long.parseLong(low);
    }
  }

  private final List<Range> ranges;
  private final String source;

  private IinRangeTable(List<Range> ranges, String source) {
    this.ranges = List.copyOf(ranges);
    this.source = source;
  }

  /**
   * Lee una tabla.
   *
   * @param data los rangos (ver el comentario de la clase)
   * @param source nombre, versión y fecha de la tabla, tal como se registran en auditoría (por
   *     ejemplo {@code "processor-iin 2026-10 (received 2026-10-01)"})
   * @throws IllegalArgumentException para una línea mal formada, con su número
   */
  public static IinRangeTable parse(Reader data, String source) {
    Objects.requireNonNull(source, "source");
    if (source.isBlank()) {
      throw new IllegalArgumentException("The IIN table needs its source and version");
    }
    List<Range> ranges = new ArrayList<>();
    try (BufferedReader in = new BufferedReader(data)) {
      String line;
      int number = 0;
      while ((line = in.readLine()) != null) {
        number++;
        String l = line.strip();
        if (l.isEmpty() || l.startsWith("#")) {
          continue;
        }
        ranges.add(range(l, number));
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read the IIN table " + source, e);
    }
    if (ranges.isEmpty()) {
      throw new IllegalArgumentException("The IIN table " + source + " has no ranges");
    }
    // primero el más específico: prefijo más largo y luego rango más estrecho
    ranges.sort(
        Comparator.comparingInt((Range r) -> -r.low().length()).thenComparingLong(Range::width));
    return new IinRangeTable(ranges, source);
  }

  private static Range range(String line, int number) {
    String[] f = line.split(",", -1);
    if (f.length != 3) {
      throw malformed(number, "expected <low>,<high>,<scheme>");
    }
    String low = f[0].strip();
    String high = f[1].strip();
    String scheme = f[2].strip().toUpperCase(Locale.ROOT);
    if (low.isEmpty()
        || low.length() > MAX_PREFIX
        || low.length() != high.length()
        || !CheckDigits.allDigits(low)
        || !CheckDigits.allDigits(high)) {
      throw malformed(number, "low and high must be prefixes of 1 to 11 digits of the same length");
    }
    if (low.compareTo(high) > 0) {
      throw malformed(number, "low is greater than high");
    }
    CardBrand brand;
    try {
      brand = CardBrand.valueOf(scheme);
    } catch (IllegalArgumentException e) {
      throw malformed(number, "unknown scheme " + scheme);
    }
    if (brand == CardBrand.UNKNOWN) {
      throw malformed(number, "UNKNOWN is not a scheme");
    }
    return new Range(low, high, brand);
  }

  private static IllegalArgumentException malformed(int line, String why) {
    return new IllegalArgumentException("IIN table line " + line + ": " + why);
  }

  @Override
  public Optional<CardBrand> resolve(String pan) {
    if (pan.isEmpty() || !CheckDigits.allDigits(pan)) {
      return Optional.empty();
    }
    for (Range r : ranges) {
      if (r.contains(pan)) {
        return Optional.of(r.brand());
      }
    }
    return Optional.empty();
  }

  @Override
  public String source() {
    return source;
  }

  /** Cantidad de rangos cargados (para el log de arranque y los detalles de health). */
  public int size() {
    return ranges.size();
  }
}
