package pe.axiz.spring.secrets;

import pe.axiz.core.secrets.SecretResolver;

/**
 * Fuente de secretos aportada por un adaptador; las fuentes se consultan en orden de beans después
 * de los archivos montados y del entorno.
 *
 * @param name nombre de la fuente para diagnóstico
 * @param resolver resolvedor
 */
public record SecretSource(String name, SecretResolver resolver) {}
