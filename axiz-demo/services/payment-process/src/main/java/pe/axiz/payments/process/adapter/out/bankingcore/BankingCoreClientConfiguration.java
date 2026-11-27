package pe.axiz.payments.process.adapter.out.bankingcore;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.HttpServiceGroup;
import org.springframework.web.service.registry.ImportHttpServices;

/** Registra {@link BankingCoreClient} en el grupo de servicios HTTP {@code banking-core}. */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(
    group = "banking-core",
    types = BankingCoreClient.class,
    clientType = HttpServiceGroup.ClientType.REST_CLIENT)
public class BankingCoreClientConfiguration {}
