package pe.axiz.spring.cache;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;

/**
 * Determina quién es el invocante de una caché {@link pe.axiz.core.cache.CacheScope#CALLER}. La
 * identidad debe ser única entre todas las formas en que los invocantes se autentican ante el
 * servicio: un subject JWT solo es único dentro de su emisor, por lo que dos emisores pueden
 * entregar el mismo {@code sub} a personas distintas.
 *
 * <p>La implementación por defecto ({@link DefaultCacheCallerResolver}) conoce las autenticaciones
 * del starter de seguridad de axiz y del resource server de Spring. Un servicio con otro tipo de
 * autenticación declara un bean de este tipo; una autenticación que no puede distinguir de forma
 * segura devuelve {@code null} y la llamada se ejecuta sin la caché.
 */
@FunctionalInterface
public interface CacheCallerResolver {

  /**
   * Devuelve la identidad de un invocante autenticado.
   *
   * @param authentication la autenticación actual, autenticada y no anónima
   * @return un valor distinto para cada invocante diferente, o {@code null} para omitir la caché
   */
  @Nullable String identity(Authentication authentication);
}
