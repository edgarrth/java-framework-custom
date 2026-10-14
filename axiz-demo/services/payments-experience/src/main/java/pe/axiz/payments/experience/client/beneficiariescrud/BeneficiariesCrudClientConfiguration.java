package pe.axiz.payments.experience.client.beneficiariescrud;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.HttpServiceGroup;
import org.springframework.web.service.registry.ImportHttpServices;

/**
 * Registers {@link BeneficiariesCrudClient} in the HTTP service group {@code beneficiaries-crud}.
 */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(
    group = "beneficiaries-crud",
    types = BeneficiariesCrudClient.class,
    clientType = HttpServiceGroup.ClientType.WEB_CLIENT)
public class BeneficiariesCrudClientConfiguration {}
