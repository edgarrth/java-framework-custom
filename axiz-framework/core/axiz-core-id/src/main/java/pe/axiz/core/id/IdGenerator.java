package pe.axiz.core.id;

/** Genera identificadores únicos como cadenas. */
@FunctionalInterface
public interface IdGenerator {

  String newId();

  static IdGenerator uuidV7() {
    return () -> UuidV7.generate().toString();
  }

  static IdGenerator ulid() {
    return Ulid::generate;
  }
}
