package pe.axiz.payments.experience.client.paymentsbusiness;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.HttpServiceGroup;
import org.springframework.web.service.registry.ImportHttpServices;

/** Registers {@link PaymentsBusinessClient} in the HTTP service group {@code payments-business}. */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(
    group = "payments-business",
    types = PaymentsBusinessClient.class,
    clientType = HttpServiceGroup.ClientType.WEB_CLIENT)
public class PaymentsBusinessClientConfiguration {}
