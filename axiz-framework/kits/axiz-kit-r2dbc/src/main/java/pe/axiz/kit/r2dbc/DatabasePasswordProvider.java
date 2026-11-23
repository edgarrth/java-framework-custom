package pe.axiz.kit.r2dbc;

/**
 * Suministra la contraseña de cada conexión nueva a la base de datos; permite al pool usar
 * credenciales de vida corta (tokens de autenticación IAM) sin reiniciar.
 */
@FunctionalInterface
public interface DatabasePasswordProvider {

  CharSequence password();
}
