package pe.axiz.payments.ux.client.beneficiaries;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.HttpServiceGroup;
import org.springframework.web.service.registry.ImportHttpServices;

/** Registra {@link BeneficiariesClient} en el grupo de servicios HTTP {@code beneficiaries}. */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(
    group = "beneficiaries",
    types = BeneficiariesClient.class,
    clientType = HttpServiceGroup.ClientType.WEB_CLIENT)
public class BeneficiariesClientConfiguration {}
