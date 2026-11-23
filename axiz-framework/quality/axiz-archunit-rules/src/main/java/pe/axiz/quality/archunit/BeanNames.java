package pe.axiz.quality.archunit;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Nombres de beans tal como Spring los deriva para los componentes escaneados (el nombre simple con
 * la inicial en minúscula, o el {@code value} del estereotipo) y para los métodos {@code @Bean} (el
 * nombre del método, o {@code name}/{@code value}). Un componente cuyo nombre también lo usa otro
 * componente o un método {@code @Bean} es una infracción: el contexto se niega a arrancar con "A
 * bean with that name has already been defined", algo que ningún compilador ni prueba unitaria
 * detecta.
 */
final class BeanNames extends ArchCondition<JavaClass> {

  private final String component;
  private final String bean;
  private final String autoConfiguration;
  private final Map<String, List<String>> owners = new LinkedHashMap<>();

  BeanNames(String component, String bean, String autoConfiguration) {
    super("have a bean name no other component or @Bean method uses");
    this.component = component;
    this.bean = bean;
    this.autoConfiguration = autoConfiguration;
  }

  @Override
  public void init(Collection<JavaClass> all) {
    owners.clear();
    for (JavaClass c : all) {
      componentName(c)
          .ifPresent(n -> owners.computeIfAbsent(n, k -> new ArrayList<>()).add(c.getName()));
      for (JavaMethod m : c.getMethods()) {
        if (m.isAnnotatedWith(bean)) {
          for (String n : beanNames(m)) {
            owners
                .computeIfAbsent(n, k -> new ArrayList<>())
                .add("@Bean " + c.getSimpleName() + "." + m.getName() + "()");
          }
        }
      }
    }
  }

  @Override
  public void check(JavaClass c, ConditionEvents events) {
    componentName(c)
        .ifPresent(
            n -> {
              List<String> defined = owners.getOrDefault(n, List.of());
              if (defined.size() > 1) {
                events.add(
                    SimpleConditionEvent.violated(
                        c, "bean name '" + n + "' is defined by " + String.join(", ", defined)));
              }
            });
  }

  /**
   * El nombre de un componente encontrado por el escaneo del classpath; vacío para cualquier otra
   * clase.
   */
  Optional<String> componentName(JavaClass c) {
    if (c.isAnnotation() || importedByAutoConfiguration(c)) {
      return Optional.empty();
    }
    for (JavaAnnotation<JavaClass> a : c.getAnnotations()) {
      JavaClass type = a.getRawType();
      if (type.getName().equals(component) || type.isMetaAnnotatedWith(component)) {
        Optional<String> explicit = a.get("value").map(String::valueOf).filter(v -> !v.isEmpty());
        return Optional.of(explicit.orElseGet(() -> defaultName(c)));
      }
    }
    return Optional.empty();
  }

  /**
   * Las autoconfiguraciones y sus clases anidadas se registran por nombre totalmente calificado.
   */
  private boolean importedByAutoConfiguration(JavaClass c) {
    for (Optional<JavaClass> k = Optional.of(c); k.isPresent(); k = k.get().getEnclosingClass()) {
      if (k.get().isAnnotatedWith(autoConfiguration)) {
        return true;
      }
    }
    return false;
  }

  private List<String> beanNames(JavaMethod m) {
    JavaAnnotation<JavaMethod> a = m.getAnnotationOfType(bean);
    List<String> names = new ArrayList<>();
    for (String attribute : List.of("name", "value")) {
      a.get(attribute)
          .filter(String[].class::isInstance)
          .map(String[].class::cast)
          .ifPresent(values -> names.addAll(List.of(values)));
    }
    return names.isEmpty() ? List.of(m.getName()) : names;
  }

  /**
   * El valor por defecto de Spring: el nombre corto de la clase ("Outer.Inner"), con la inicial en
   * minúscula como una propiedad.
   */
  static String defaultName(JavaClass c) {
    String name =
        c.getName().substring(c.getPackageName().isEmpty() ? 0 : c.getPackageName().length() + 1);
    name = name.replace('$', '.');
    if (name.length() > 1
        && Character.isUpperCase(name.charAt(0))
        && Character.isUpperCase(name.charAt(1))) {
      return name;
    }
    return Character.toLowerCase(name.charAt(0)) + name.substring(1);
  }
}
