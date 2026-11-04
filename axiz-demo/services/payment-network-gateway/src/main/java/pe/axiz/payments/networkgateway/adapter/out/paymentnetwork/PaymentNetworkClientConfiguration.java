package pe.axiz.payments.networkgateway.adapter.out.paymentnetwork;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.HttpServiceGroup;
import org.springframework.web.service.registry.ImportHttpServices;

/** Registers {@link PaymentNetworkClient} in the HTTP service group {@code payment-network}. */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(
    group = "payment-network",
    types = PaymentNetworkClient.class,
    clientType = HttpServiceGroup.ClientType.REST_CLIENT)
public class PaymentNetworkClientConfiguration {}
