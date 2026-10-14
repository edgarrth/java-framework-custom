package pe.axiz.payments.process.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.axiz.core.saga.ContextCodec;
import pe.axiz.core.saga.SagaDefinition;
import pe.axiz.core.saga.SagaRecovery;
import pe.axiz.payments.process.application.port.out.Accounts;
import pe.axiz.payments.process.application.port.out.PaymentOrders;
import pe.axiz.payments.process.application.port.out.Transfers;
import pe.axiz.payments.process.application.saga.PaymentContext;
import pe.axiz.payments.process.application.saga.PaymentSaga;
import pe.axiz.spring.saga.JacksonContextCodec;
import tools.jackson.databind.json.JsonMapper;

/** Wires the payment saga to its adapters and makes it resumable. */
@Configuration(proxyBeanMethods = false)
class PaymentSagaConfiguration {

  @Bean
  ContextCodec<PaymentContext> paymentContextCodec(JsonMapper mapper) {
    return new JacksonContextCodec<>(mapper, PaymentContext.class);
  }

  /** The saga definition, registered for recovery of interrupted processes. */
  @Bean
  SagaDefinition<PaymentContext> paymentSaga(
      PaymentOrders orders,
      Accounts accounts,
      Transfers transfers,
      SagaRecovery recovery,
      ContextCodec<PaymentContext> paymentContextCodec) {
    SagaDefinition<PaymentContext> saga = PaymentSaga.definition(orders, accounts, transfers);
    recovery.register(saga, paymentContextCodec);
    return saga;
  }
}
