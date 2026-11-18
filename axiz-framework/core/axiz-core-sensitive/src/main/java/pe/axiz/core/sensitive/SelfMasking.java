package pe.axiz.core.sensitive;

/** A sensitive value whose safe representation is used without inspecting its contents. */
public interface SelfMasking {
  String masked();
}
