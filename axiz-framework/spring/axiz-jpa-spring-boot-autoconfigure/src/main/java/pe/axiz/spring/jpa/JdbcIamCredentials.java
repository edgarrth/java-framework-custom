package pe.axiz.spring.jpa;

import com.zaxxer.hikari.HikariDataSource;
import java.util.ArrayList;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;
import pe.axiz.spring.foundation.aws.AwsCapability;
import pe.axiz.spring.foundation.aws.AwsIdentity;
import pe.axiz.spring.foundation.aws.AwsService;
import software.amazon.jdbc.profile.ConfigurationProfileBuilder;
import software.amazon.jdbc.profile.DriverConfigurationProfiles;

/** Pool-scoped identity bridge with deterministic removal on pool/context shutdown. */
final class JdbcIamCredentials implements DestructionAwareBeanPostProcessor, DisposableBean {
  private final AwsIdentity identity;

  private final ConcurrentHashMap<HikariDataSource, String> profiles = new ConcurrentHashMap<>();

  JdbcIamCredentials(AwsIdentity identity) {
    this.identity = identity;
  }

  @Override
  public Object postProcessBeforeInitialization(Object bean, String name) {
    if (bean instanceof HikariDataSource pool
        && "software.amazon.jdbc.Driver".equals(pool.getDriverClassName())) {
      if (profiles.containsKey(pool)) return bean;
      String configured = pool.getDataSourceProperties().getProperty("wrapperProfileName", "");
      if (configured.startsWith(JdbcIamCredentialScopes.PREFIX))
        throw new IllegalStateException("JDBC IAM pool belongs to an unknown credential scope");
      if (!"axiz-jpa-iam".equals(configured) && !"axiz-jpa-iam-split".equals(configured))
        return bean;
      if (pool.isRunning())
        throw new IllegalStateException(
            "Configure JDBC IAM credentials before starting Hikari: " + name);
      profiles.computeIfAbsent(
          pool,
          ignored -> {
            var definition = DriverConfigurationProfiles.getProfileConfiguration(configured);
            if (definition == null)
              throw new IllegalStateException("Framework JDBC IAM profile is not registered");
            String id =
                JdbcIamCredentialScopes.open(identity.source(AwsService.RDS, AwsCapability.JPA));
            try {
              var profile =
                  ConfigurationProfileBuilder.get()
                      .withName(id)
                      .withPluginFactories(new ArrayList<>(definition.getPluginFactories()))
                      .withProperties(new Properties())
                      .build();
              DriverConfigurationProfiles.addOrReplaceProfile(id, profile);
              pool.addDataSourceProperty("wrapperProfileName", id);
              return id;
            } catch (RuntimeException | Error failure) {
              DriverConfigurationProfiles.remove(id);
              JdbcIamCredentialScopes.close(id);
              throw failure;
            }
          });
    }
    return bean;
  }

  @Override
  public boolean requiresDestruction(Object bean) {
    return bean instanceof HikariDataSource pool && profiles.containsKey(pool);
  }

  @Override
  public void postProcessBeforeDestruction(Object bean, String name) {
    if (bean instanceof HikariDataSource pool) release(pool);
  }

  private void release(HikariDataSource pool) {
    String id = profiles.remove(pool);
    if (id == null) return;
    try {
      pool.close();
    } finally {
      DriverConfigurationProfiles.remove(id);
      JdbcIamCredentialScopes.close(id);
    }
  }

  @Override
  public void destroy() {
    RuntimeException failure = null;
    for (var pool : profiles.keySet()) {
      try {
        release(pool);
      } catch (RuntimeException close) {
        if (failure == null) failure = close;
        else failure.addSuppressed(close);
      }
    }
    if (failure != null) throw failure;
  }
}
