package pe.axiz.payments.process.adapter.out.bankingcore;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.HttpServiceGroup;
import org.springframework.web.service.registry.ImportHttpServices;

/** Registers {@link BankingCoreClient} in the HTTP service group {@code banking-core}. */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(
    group = "banking-core",
    types = BankingCoreClient.class,
    clientType = HttpServiceGroup.ClientType.REST_CLIENT)
public class BankingCoreClientConfiguration {}
