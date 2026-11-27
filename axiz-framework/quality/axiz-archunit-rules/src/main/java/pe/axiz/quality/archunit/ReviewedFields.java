package pe.axiz.quality.archunit;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Collection;
import java.util.Map;

/**
 * Evidencia de revisión para los tipos declarados mutables, no una prueba estática de seguridad
 * entre hilos.
 */
final class ReviewedFields extends ArchCondition<JavaField> {
  static final DescribedPredicate<JavaClass> MUTABLE =
      DescribedPredicate.describe(
          "collection, map, array, builder or atomic",
          type ->
              type.isArray()
                  || type.isAssignableTo(Collection.class)
                  || type.isAssignableTo(Map.class)
                  || type.isAssignableTo(StringBuilder.class)
                  || type.isAssignableTo(StringBuffer.class)
                  || type.getPackageName().equals("java.util.concurrent.atomic"));

  ReviewedFields() {
    super("have an explicit nonblank review of shared mutable state");
  }

  @Override
  public void check(JavaField field, ConditionEvents events) {
    boolean reviewed =
        field
            .tryGetAnnotationOfType("pe.axiz.core.commons.ReviewedState")
            .flatMap(annotation -> annotation.get("reason"))
            .filter(reason -> reason instanceof String text && !text.isBlank())
            .isPresent();
    events.add(
        new SimpleConditionEvent(
            field,
            reviewed,
            field.getFullName() + " requires @ReviewedState with a concrete reason"));
  }
}
