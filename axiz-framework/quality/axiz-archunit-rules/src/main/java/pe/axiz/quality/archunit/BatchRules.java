package pe.axiz.quality.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import java.util.List;

/** Batch style: readers, processors and writers in {@code job}; no HTTP controllers. */
public final class BatchRules {

  private BatchRules() {}

  public static List<ArchRule> rules(String basePackage) {
    return List.of(
        classes()
            .that()
            .haveSimpleNameEndingWith("Processor")
            .and()
            .resideInAPackage(basePackage + "..")
            .should()
            .resideInAPackage(basePackage + ".job.."),
        noClasses()
            .that()
            .resideInAPackage(basePackage + "..")
            .should()
            .beAnnotatedWith("org.springframework.web.bind.annotation.RestController")
            .because("batch services run as CronJobs without an API"));
  }
}
