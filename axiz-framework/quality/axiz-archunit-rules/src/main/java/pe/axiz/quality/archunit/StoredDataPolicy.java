package pe.axiz.quality.archunit;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaEnumConstant;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.sensitive.SensitiveKeys;
import pe.axiz.core.sensitive.SensitivityLevel;

/** Bounded traversal of stored/published local object graphs and actual persistence mappings. */
final class StoredDataPolicy extends ArchCondition<JavaClass> {
  private static final SensitiveKeys KEYS = SensitiveKeys.defaults();
  private static final String SAD = "pe.axiz.core.carddata.SensitiveAuthenticationData";
  private static final String PAN = "pe.axiz.core.carddata.Pan";
  private static final String SENSITIVE = "pe.axiz.core.sensitive.Sensitive";
  private static final Set<String> SAGA =
      Set.of(
          "pe.axiz.core.saga.SagaDefinition",
          "pe.axiz.core.saga.SagaStep",
          "pe.axiz.core.saga.ContextCodec",
          "pe.axiz.core.saga.SagaResult");
  private final String base;
  private final CardDataScope scope;
  private Collection<JavaClass> imported = List.of();
  private Set<String> sagaContexts = Set.of();

  StoredDataPolicy(String base, CardDataScope scope) {
    super(
        "protect sensitive persisted fields and exclude authentication data from stored or"
            + " published graphs");
    this.base = base;
    this.scope = scope;
  }

  @Override
  public void init(Collection<JavaClass> classes) {
    imported = List.copyOf(classes);
    Set<String> contexts = new HashSet<>();
    for (JavaClass type : classes) {
      type.getInterfaces().forEach(signature -> collectSaga(signature, contexts));
      type.getFields().forEach(field -> collectSaga(field.getType(), contexts));
      type.getMethods()
          .forEach(
              method -> {
                collectSaga(method.getReturnType(), contexts);
                method.getParameterTypes().forEach(signature -> collectSaga(signature, contexts));
              });
    }
    sagaContexts = Set.copyOf(contexts);
  }

  private void collectSaga(JavaType signature, Set<String> contexts) {
    var involved = signature.getAllInvolvedRawTypes();
    if (involved.stream().anyMatch(t -> SAGA.contains(t.getName())))
      involved.stream().filter(this::owned).forEach(t -> contexts.add(t.getName()));
  }

  private boolean owned(JavaClass type) {
    return type.getPackageName().equals(base) || type.getPackageName().startsWith(base + ".");
  }

  @Override
  public void check(JavaClass type, ConditionEvents events) {
    boolean persistent = PersistenceProtection.mapped(type);
    if (persistent
        || type.isAssignableTo("pe.axiz.core.domain.DomainEvent")
        || ("." + type.getPackageName() + ".").contains(".event.")
        || sagaContexts.contains(type.getName()))
      walk(
          type,
          type,
          persistent,
          type.isAnnotatedWith(PersistenceProtection.DYNAMO),
          PersistenceProtection.propertyAccess(type, false),
          0,
          new HashSet<>(),
          events);
  }

  private void walk(
      JavaClass root,
      JavaClass current,
      boolean persistent,
      boolean dynamo,
      boolean inheritedAccess,
      int depth,
      Set<String> path,
      ConditionEvents events) {
    if (!path.add(current.getName())) return;
    if (!current.isFullyImported()) {
      events.add(
          SimpleConditionEvent.violated(
              root,
              root.getName()
                  + " reaches an incompletely imported stored type "
                  + current.getName()));
      path.remove(current.getName());
      return;
    }
    if (depth > 4) {
      events.add(
          SimpleConditionEvent.violated(
              root,
              root.getName()
                  + " exceeds the four-level stored object graph limit at "
                  + current.getName()));
      path.remove(current.getName());
      return;
    }
    boolean propertyAccess = PersistenceProtection.propertyAccess(current, inheritedAccess);
    for (JavaField field : current.getAllFields()) {
      if (field.getModifiers().contains(JavaModifier.STATIC)) continue;
      var involved = field.getType().getAllInvolvedRawTypes();
      for (JavaClass raw : involved) {
        JavaClass type = raw.getBaseComponentType();
        if (type.isAssignableTo(SAD)
            || (scope != CardDataScope.VAULT && type.getName().equals(PAN)))
          events.add(
              SimpleConditionEvent.violated(
                  field,
                  root.getName()
                      + " cannot store/publish "
                      + type.getName()
                      + " through "
                      + field.getFullName()));
        if (owned(type) && !type.isPrimitive() && !type.isEnum())
          walk(root, type, persistent, dynamo, propertyAccess, depth + 1, path, events);
      }
      if (persistent
          && sensitive(field)
          && !PersistenceProtection.encrypted(field, propertyAccess, dynamo, imported))
        events.add(
            SimpleConditionEvent.violated(
                field,
                field.getFullName()
                    + " requires a protected value type or an effective encrypting converter"));
    }
    if (persistent) {
      for (var getter : current.getAllMethods()) {
        if (getter.getModifiers().contains(JavaModifier.STATIC)
            || getter.getOwner().getPackageName().startsWith("java.")
            || !PersistenceProtection.propertyGetter(getter, propertyAccess, dynamo)) continue;
        String name = getter.getName();
        int prefix = name.startsWith("get") ? 3 : name.startsWith("is") ? 2 : 0;
        if (prefix == 0 || name.length() <= prefix) continue;
        String attribute = Character.toLowerCase(name.charAt(prefix)) + name.substring(prefix + 1);
        if (current.getAllFields().stream().anyMatch(field -> field.getName().equals(attribute)))
          continue;
        boolean named =
            KEYS.find(attribute)
                .map(
                    k ->
                        k.level() == SensitivityLevel.HIGH
                            || k.level() == SensitivityLevel.CRITICAL)
                .orElse(false);
        if ((named || annotated(getter.tryGetAnnotationOfType(SENSITIVE).orElse(null)))
            && !PersistenceProtection.encryptedGetter(getter, dynamo, imported))
          events.add(
              SimpleConditionEvent.violated(
                  getter,
                  getter.getFullName()
                      + " requires an effective encrypting converter for its stored property"));
        for (var raw : getter.getReturnType().getAllInvolvedRawTypes()) {
          var type = raw.getBaseComponentType();
          if (type.isAssignableTo(SAD)
              || (scope != CardDataScope.VAULT && type.getName().equals(PAN)))
            events.add(
                SimpleConditionEvent.violated(
                    getter,
                    root.getName()
                        + " cannot store "
                        + type.getName()
                        + " through "
                        + getter.getFullName()));
          if (owned(type) && !type.isPrimitive() && !type.isEnum())
            walk(root, type, persistent, dynamo, propertyAccess, depth + 1, path, events);
        }
      }
    }
    path.remove(current.getName());
  }

  private static boolean sensitive(JavaField field) {
    boolean named =
        KEYS.find(field.getName())
            .map(k -> k.level() == SensitivityLevel.HIGH || k.level() == SensitivityLevel.CRITICAL)
            .orElse(false);
    if (named || annotated(field.tryGetAnnotationOfType(SENSITIVE).orElse(null))) return true;
    return PersistenceProtection.getter(field)
        .map(m -> annotated(m.tryGetAnnotationOfType(SENSITIVE).orElse(null)))
        .orElse(false);
  }

  private static boolean annotated(@Nullable JavaAnnotation<?> annotation) {
    if (annotation == null) return false;
    return annotation
        .get("level")
        .filter(
            level ->
                level instanceof JavaEnumConstant value
                    && Set.of("HIGH", "CRITICAL").contains(value.name()))
        .isPresent();
  }
}
