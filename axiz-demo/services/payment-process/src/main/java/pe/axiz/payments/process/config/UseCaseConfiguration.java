package pe.axiz.payments.process.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import pe.axiz.structure.hexagonal.annotation.UseCase;

/**
 * Registra como beans las clases {@code @UseCase} de la capa de aplicación, de modo que esa capa no
 * necesita estereotipos de Spring.
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan(
    basePackages = "pe.axiz.payments.process.application",
    useDefaultFilters = false,
    includeFilters = @ComponentScan.Filter(UseCase.class))
public class UseCaseConfiguration {}
