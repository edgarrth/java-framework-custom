package pe.axiz.payments.process.adapter.out.coreintegration;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.HttpServiceGroup;
import org.springframework.web.service.registry.ImportHttpServices;

/** Registers {@link CoreIntegrationClient} in the HTTP service group {@code core-integration}. */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(
    group = "core-integration",
    types = CoreIntegrationClient.class,
    clientType = HttpServiceGroup.ClientType.REST_CLIENT)
public class CoreIntegrationClientConfiguration {}
