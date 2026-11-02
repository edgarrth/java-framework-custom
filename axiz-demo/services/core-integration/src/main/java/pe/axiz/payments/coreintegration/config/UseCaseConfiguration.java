package pe.axiz.payments.coreintegration.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import pe.axiz.structure.hexagonal.annotation.UseCase;

/**
 * Registers the {@code @UseCase} classes of the application layer as beans, so the application
 * layer needs no Spring stereotype.
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan(
    basePackages = "pe.axiz.payments.coreintegration.application",
    useDefaultFilters = false,
    includeFilters = @ComponentScan.Filter(UseCase.class))
public class UseCaseConfiguration {}
