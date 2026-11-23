package pe.axiz.core.security;

import java.time.Duration;

/**
 * Puerto del limitador de tasa. Las claves de llamador provienen de identidades validadas; las
 * cuotas anónimas configuradas de forma explícita usan la dirección del par de transporte. Las
 * cabeceras del cliente nunca suministran ninguna de las dos identidades.
 */
public interface RateLimiter {

  /**
   * Intenta consumir un permiso.
   *
   * @param key clave del limitador, ver {@link RateLimitKeys}
   * @param limit permisos por ventana
   * @param window longitud de la ventana
   * @return la decisión
   */
  Decision tryAcquire(String key, long limit, Duration window);

  /**
   * Intenta consumir un permiso con una política explícita para cuando el almacén del limitador no
   * está disponible: un grupo de rutas puede fallar en cerrado (operaciones financieras, callbacks)
   * mientras otros fallan en abierto. Un limitador cuyo almacén no puede fallar (en memoria) ignora
   * la política.
   *
   * @param failOpen indica si la petición pasa cuando el almacén no puede responder
   */
  default Decision tryAcquire(String key, long limit, Duration window, boolean failOpen) {
    return tryAcquire(key, limit, window);
  }

  /**
   * Decisión del limitador.
   *
   * @param allowed indica si la petición puede continuar
   * @param remaining permisos restantes en la ventana
   * @param retryAfter tiempo hasta que haya un permiso disponible, cuando se rechaza
   */
  record Decision(boolean allowed, long remaining, Duration retryAfter) {}
}
