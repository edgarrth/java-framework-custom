package pe.axiz.core.commons;

import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Perfil operativo de un despliegue ({@code axiz.config.environment}). El estado guardado en un
 * proceso (claves de idempotencia, locks y almacenamiento de objetos en memoria) solo es correcto
 * con una única réplica y sin reinicios, por lo que se acepta en entornos {@link #SINGLE_PROCESS} y
 * se rechaza en cualquier otro: un entorno desplegado debe usar un almacén compartido para las
 * garantías que declara el framework.
 *
 * <p>Es también la lista común de permitidos para excepciones de transporte solicitadas de forma
 * explícita (TLS de HTTP y de almacenes de datos), bases de datos embebidas, modos de JPA que
 * cambian el esquema, política CORS con credenciales y valores por defecto de auditoría local. Los
 * perfiles activos de Spring nunca conceden estas excepciones. Cada integración debe igualmente
 * exigir su propio flag o selector de excepción explícito cuando corresponda.
 */
public final class Environments {

  /** Entornos donde el estado por proceso es aceptable. */
  public static final Set<String> SINGLE_PROCESS = Set.of("local", "test", "ci");

  private Environments() {}

  public static boolean allowsInMemoryState(@Nullable String environment) {
    return environment != null
        && SINGLE_PROCESS.contains(environment.strip().toLowerCase(Locale.ROOT));
  }

  /**
   * Falla cuando {@code environment} es un entorno desplegado o no está definido.
   *
   * @param setting la propiedad que seleccionó la implementación en memoria, por ejemplo {@code
   *     axiz.lock.store=memory}
   * @param alternatives qué usar en su lugar, por ejemplo {@code redis or dynamodb}
   * @param reason la garantía que rompería un almacén por proceso
   */
  public static void requireSingleProcess(
      @Nullable String environment, String setting, String alternatives, String reason) {
    if (!allowsInMemoryState(environment)) {
      // un entorno no definido nunca se toma por uno local: un despliegue que perdió la
      // configuración no debe recurrir al estado por proceso
      String current =
          environment == null || environment.isBlank()
              ? "axiz.config.environment is not set; set it to local or test where one process"
                  + " runs"
              : "axiz.config.environment=" + environment;
      throw new IllegalStateException(
          setting
              + " is only allowed in "
              + SINGLE_PROCESS.stream().sorted().toList()
              + " ("
              + current
              + "): "
              + reason
              + "; use "
              + alternatives);
    }
  }
}
