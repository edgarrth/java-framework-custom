package pe.axiz.kit.r2dbc;

/**
 * Supplies the password of each new database connection; lets the pool use short-lived credentials
 * (IAM authentication tokens) without restarting.
 */
@FunctionalInterface
public interface DatabasePasswordProvider {

  CharSequence password();
}
