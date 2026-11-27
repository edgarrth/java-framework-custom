package pe.axiz.core.sensitive;

/** Valor sensible cuya representación segura se usa sin inspeccionar su contenido. */
public interface SelfMasking {
  String masked();
}
