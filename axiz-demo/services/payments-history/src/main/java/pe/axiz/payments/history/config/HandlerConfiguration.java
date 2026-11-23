package pe.axiz.payments.history.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import pe.axiz.extension.cqrs.AccessPolicy;
import pe.axiz.extension.cqrs.CommandHandler;
import pe.axiz.extension.cqrs.QueryHandler;
import pe.axiz.extension.cqrs.ReactiveAccessPolicy;
import pe.axiz.extension.cqrs.ReactiveCommandHandler;
import pe.axiz.extension.cqrs.ReactiveQueryHandler;

/** Registra los handlers y las políticas de aplicación planos desde la raíz de composición. */
@Configuration(proxyBeanMethods = false)
@ComponentScan(
    basePackages = "pe.axiz.payments.history.application",
    useDefaultFilters = false,
    includeFilters =
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = {
              CommandHandler.class,
              QueryHandler.class,
              ReactiveCommandHandler.class,
              ReactiveQueryHandler.class,
              AccessPolicy.class,
              ReactiveAccessPolicy.class
            }))
public class HandlerConfiguration {}
