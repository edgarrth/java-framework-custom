package pe.axiz.payments.networkgateway.adapter.out.paymentnetwork;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.HttpServiceGroup;
import org.springframework.web.service.registry.ImportHttpServices;

/** Registra {@link PaymentNetworkClient} en el grupo de servicios HTTP {@code payment-network}. */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(
    group = "payment-network",
    types = PaymentNetworkClient.class,
    clientType = HttpServiceGroup.ClientType.REST_CLIENT)
public class PaymentNetworkClientConfiguration {}
