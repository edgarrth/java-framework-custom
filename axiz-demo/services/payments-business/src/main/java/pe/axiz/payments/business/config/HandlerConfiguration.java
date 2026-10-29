package pe.axiz.payments.business.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import pe.axiz.framework.cqrs.AccessPolicy;
import pe.axiz.framework.cqrs.CommandHandler;
import pe.axiz.framework.cqrs.QueryHandler;
import pe.axiz.framework.cqrs.ReactiveCommandHandler;
import pe.axiz.framework.cqrs.ReactiveQueryHandler;

/** Registers plain application handlers and policies from the composition root. */
@Configuration(proxyBeanMethods = false)
@ComponentScan(
    basePackages = "pe.axiz.payments.business.application",
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
