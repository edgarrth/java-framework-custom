package pe.axiz.payments.orders.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import pe.axiz.extension.cqrs.AccessPolicy;
import pe.axiz.extension.cqrs.CommandHandler;
import pe.axiz.extension.cqrs.QueryHandler;
import pe.axiz.extension.cqrs.ReactiveCommandHandler;
import pe.axiz.extension.cqrs.ReactiveQueryHandler;

/** Registers plain application handlers and policies from the composition root. */
@Configuration(proxyBeanMethods = false)
@ComponentScan(
    basePackages = "pe.axiz.payments.orders.application",
    useDefaultFilters = false,
    includeFilters =
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = {
              CommandHandler.class,
              QueryHandler.class,
              ReactiveCommandHandler.class,
              ReactiveQueryHandler.class,
              AccessPolicy.class
            }))
public class HandlerConfiguration {}
