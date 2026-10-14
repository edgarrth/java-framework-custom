package pe.axiz.spring.tenancy;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;

/** Hibernate {@code @TenantId} support: the current tenant filters and stamps tenant entities. */
@AutoConfiguration(
    beforeName = "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration")
@ConditionalOnClass(
    name = {
      "org.hibernate.context.spi.CurrentTenantIdentifierResolver",
      "org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer"
    })
@ConditionalOnBooleanProperty(name = "axiz.tenancy.enabled")
public class AxizTenancyHibernateAutoConfiguration {

  @Bean
  HibernatePropertiesCustomizer axizTenantHibernateCustomizer() {
    return props ->
        props.put("hibernate.tenant_identifier_resolver", new TenantIdentifierResolver());
  }
}
