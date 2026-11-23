package pe.axiz.payments.bankingcore.adapter.out.corebanking;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.HttpServiceGroup;
import org.springframework.web.service.registry.ImportHttpServices;

/** Registra {@link CoreBankingClient} en el grupo de servicios HTTP {@code core-banking}. */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(
    group = "core-banking",
    types = CoreBankingClient.class,
    clientType = HttpServiceGroup.ClientType.REST_CLIENT)
public class CoreBankingClientConfiguration {}
