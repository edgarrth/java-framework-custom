package pe.axiz.spring.scheduling.jdbc;

import java.util.Map;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import pe.axiz.spring.foundation.jdbc.SchemaInitializer;
import pe.axiz.spring.scheduling.AxizSchedulingProperties;

/** JDBC scheduling resources; common policies remain in the base capability. */
@AutoConfiguration(
    beforeName = "pe.axiz.spring.scheduling.AxizSchedulingAutoConfiguration",
    afterName = {
      "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
      "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
      "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
    })
@ConditionalOnBooleanProperty(name = "axiz.scheduling.enabled", matchIfMissing = true)
@EnableConfigurationProperties({AxizSchedulingJdbcProperties.class, AxizSchedulingProperties.class})
@ConditionalOnClass(name = "net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider")
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(name = "axiz.scheduling.store", havingValue = "jdbc", matchIfMissing = true)
public class AxizSchedulingJdbcAutoConfiguration {

  @Bean
  SchemaInitializer axizShedlockSchema(DataSource dataSource, AxizSchedulingJdbcProperties p) {
    return new SchemaInitializer(
        dataSource, "shedlock", p.initializeSchema(), Map.of("shedlock", p.table()));
  }

  @Bean
  @ConditionalOnMissingBean
  LockProvider axizJdbcLockProvider(
      DataSource dataSource, AxizSchedulingJdbcProperties p, SchemaInitializer axizShedlockSchema) {
    return new JdbcTemplateLockProvider(
        JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(new JdbcTemplate(dataSource))
            .withTableName(p.table())
            .usingDbTime()
            .build());
  }
}
