package pe.axiz.core.cache;

/**
 * Quién comparte las entradas de un método {@link AxizCacheable}, además de sus argumentos.
 *
 * <p>El valor por defecto es {@link #TENANT}: en un servicio multi-tenant dos tenants nunca leen
 * las entradas del otro, incluso cuando un método recibe un id local al tenant (un código de
 * cliente, un número de página) y lee el tenant del contexto. {@link #GLOBAL} es una elección
 * deliberada para datos iguales para todos (un catálogo, una lista de bancos).
 */
public enum CacheScope {

  /**
   * Las entradas se comparten solo entre llamadas del mismo tenant (el ligado a la llamada, o
   * ninguno en un servicio de un solo tenant).
   */
  TENANT,

  /**
   * Las entradas se comparten solo entre llamadas del mismo tenant y del mismo llamador
   * autenticado, para resultados que dependen de quién pregunta. Sin un llamador autenticado el
   * método se ejecuta sin caché.
   */
  CALLER,

  /**
   * Las entradas se comparten entre todos los llamadores y tenants: solo para datos realmente
   * globales.
   */
  GLOBAL
}
