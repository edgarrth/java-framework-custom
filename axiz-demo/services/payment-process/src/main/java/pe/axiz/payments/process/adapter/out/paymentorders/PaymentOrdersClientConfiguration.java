package pe.axiz.payments.process.adapter.out.paymentorders;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.HttpServiceGroup;
import org.springframework.web.service.registry.ImportHttpServices;

/** Registers {@link PaymentOrdersClient} in the HTTP service group {@code payment-orders}. */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(
    group = "payment-orders",
    types = PaymentOrdersClient.class,
    clientType = HttpServiceGroup.ClientType.REST_CLIENT)
public class PaymentOrdersClientConfiguration {}
