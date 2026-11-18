package pe.axiz.core.tokenization;

/** Built-in textual profiles; neither imports card data nor chooses storage/cryptography. */
public final class TextTokenProfiles {
  private TextTokenProfiles() {}

  public static TokenProfile identity() {
    return new TokenProfile() {
      @Override
      public String id() {
        return "text.identity";
      }

      @Override
      public int version() {
        return 1;
      }

      @Override
      public SensitiveValue canonicalize(SensitiveValue input) {
        return input.copy();
      }
    };
  }

  public static TokenProfile dniPe() {
    return new TokenProfile() {
      @Override
      public String id() {
        return "dni.pe";
      }

      @Override
      public int version() {
        return 1;
      }

      @Override
      public SensitiveValue canonicalize(SensitiveValue input) {
        return input.use(
            chars -> {
              if (chars.length != 8) throw TokenErrors.failure(TokenErrors.INVALID);
              for (char c : chars)
                if (c < '0' || c > '9') throw TokenErrors.failure(TokenErrors.INVALID);
              return SensitiveValue.of(chars);
            });
      }
    };
  }
}
