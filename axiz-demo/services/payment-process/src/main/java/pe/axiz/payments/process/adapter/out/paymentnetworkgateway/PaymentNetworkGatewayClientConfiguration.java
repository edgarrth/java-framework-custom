package pe.axiz.payments.process.adapter.out.paymentnetworkgateway;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.HttpServiceGroup;
import org.springframework.web.service.registry.ImportHttpServices;

/**
 * Registra {@link PaymentNetworkGatewayClient} en el grupo de servicios HTTP {@code
 * payment-network-gateway}.
 */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(
    group = "payment-network-gateway",
    types = PaymentNetworkGatewayClient.class,
    clientType = HttpServiceGroup.ClientType.REST_CLIENT)
public class PaymentNetworkGatewayClientConfiguration {}
