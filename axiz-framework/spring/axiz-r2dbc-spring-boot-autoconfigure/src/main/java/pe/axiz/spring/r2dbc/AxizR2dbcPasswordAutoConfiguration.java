package pe.axiz.spring.r2dbc;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.r2dbc.autoconfigure.ConnectionFactoryOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import pe.axiz.kit.r2dbc.DatabasePasswordProvider;

/**
 * Conecta cualquier proveedor de contraseñas dinámicas al pool de escritura sin el SDK de un
 * proveedor.
 */
@AutoConfiguration(
    beforeName = "org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration",
    afterName = "pe.axiz.spring.r2dbc.aws.AxizR2dbcIamAutoConfiguration")
@ConditionalOnBean(DatabasePasswordProvider.class)
public class AxizR2dbcPasswordAutoConfiguration {
  @Bean
  ConnectionFactoryOptionsBuilderCustomizer axizR2dbcPassword(DatabasePasswordProvider passwords) {
    return options -> R2dbcPasswords.apply(options, passwords);
  }
}
