package pe.axiz.quality.archunit;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaEnumConstant;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaMethod;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/** Examines the effective JPA field/property access or DynamoDB getter mapping. */
final class PersistenceProtection {
  static final String ENTITY = "jakarta.persistence.Entity";
  static final String DYNAMO =
      "software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean";
  private static final String CONVERT = "jakarta.persistence.Convert";
  private static final String CONVERTER = "pe.axiz.kit.jpa.EncryptedConverter";
  private static final String ACCESS = "jakarta.persistence.Access";
  private static final Set<String> PROTECTED =
      Set.of(
          "pe.axiz.core.tokenization.Token",
          "pe.axiz.core.carddata.TruncatedPan",
          "pe.axiz.core.crypto.EncryptedValue",
          "pe.axiz.core.crypto.KeyedHash");

  private PersistenceProtection() {}

  static boolean mapped(JavaClass type) {
    return type.isAnnotatedWith(ENTITY) || type.isAnnotatedWith(DYNAMO);
  }

  static boolean propertyAccess(JavaClass type, boolean inherited) {
    Optional<JavaAnnotation<JavaClass>> access = type.tryGetAnnotationOfType(ACCESS);
    if (access.isPresent()) return property(access.get());
    if (type.getAllMethods().stream()
        .anyMatch(
            m ->
                m.isAnnotatedWith("jakarta.persistence.Id")
                    || m.isAnnotatedWith("jakarta.persistence.EmbeddedId"))) return true;
    if (type.getAllFields().stream()
        .anyMatch(
            f ->
                f.isAnnotatedWith("jakarta.persistence.Id")
                    || f.isAnnotatedWith("jakarta.persistence.EmbeddedId"))) return false;
    return inherited;
  }

  private static boolean property(JavaAnnotation<?> access) {
    return access
        .get("value")
        .filter(
            value ->
                value instanceof JavaEnumConstant constant && constant.name().equals("PROPERTY"))
        .isPresent();
  }

  static Optional<JavaMethod> getter(JavaField field) {
    String capital =
        Character.toUpperCase(field.getName().charAt(0)) + field.getName().substring(1);
    return field.getOwner().getAllMethods().stream()
        .filter(m -> m.getRawParameterTypes().isEmpty())
        .filter(
            m ->
                m.getName().equals("get" + capital)
                    || m.getName().equals("is" + capital)
                    || (field.getOwner().isRecord() && m.getName().equals(field.getName())))
        .filter(m -> m.getRawReturnType().equals(field.getRawType()))
        .findFirst();
  }

  static boolean encrypted(
      JavaField field, boolean propertyAccess, boolean dynamo, Collection<JavaClass> converters) {
    if (PROTECTED.contains(field.getRawType().getName())) return true;
    var getter = getter(field);
    if (dynamo)
      return getter
          .flatMap(
              m ->
                  m.tryGetAnnotationOfType(
                      "software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy"))
          .flatMap(a -> a.get("value"))
          .filter(
              c ->
                  c instanceof JavaClass type
                      && type.isAssignableTo("pe.axiz.kit.dynamodb.EncryptedAttributeConverter"))
          .isPresent();
    boolean fieldOverride =
        field.tryGetAnnotationOfType(ACCESS).filter(a -> !property(a)).isPresent();
    boolean getterOverride =
        getter
            .flatMap(m -> m.tryGetAnnotationOfType(ACCESS))
            .filter(PersistenceProtection::property)
            .isPresent();
    if (fieldOverride && getterOverride) return false;
    JavaMember effective =
        (getterOverride || (propertyAccess && !fieldOverride)) ? getter.orElse(null) : field;
    if (effective == null) return false;
    return converted(effective, field.getRawType(), converters);
  }

  static boolean propertyGetter(JavaMethod method, boolean defaultProperty, boolean dynamo) {
    return method.getRawParameterTypes().isEmpty()
        && (dynamo
            || defaultProperty
            || method
                .tryGetAnnotationOfType(ACCESS)
                .filter(PersistenceProtection::property)
                .isPresent());
  }

  static boolean encryptedGetter(
      JavaMethod method, boolean dynamo, Collection<JavaClass> converters) {
    if (PROTECTED.contains(method.getRawReturnType().getName())) return true;
    if (dynamo)
      return method
              .tryGetAnnotationOfType(
                  "software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy")
              .flatMap(a -> a.get("value"))
              .filter(
                  c ->
                      c instanceof JavaClass type
                          && type.isAssignableTo(
                              "pe.axiz.kit.dynamodb.EncryptedAttributeConverter"))
              .isPresent()
          && method.getRawReturnType().isEquivalentTo(String.class);
    return converted(method, method.getRawReturnType(), converters);
  }

  private static boolean converted(
      JavaMember effective, JavaClass attributeType, Collection<JavaClass> converters) {
    if (!attributeType.isEquivalentTo(String.class)) return false;
    var conversion = effective.tryGetAnnotationOfType(CONVERT);
    if (conversion.isPresent()) {
      var annotation = conversion.get();
      if (annotation.get("disableConversion").filter(Boolean.TRUE::equals).isPresent())
        return false;
      return annotation
          .get("converter")
          .filter(c -> c instanceof JavaClass type && type.isAssignableTo(CONVERTER))
          .isPresent();
    }
    // EncryptedConverter's input contract is String. An autoApply converter for another
    // attribute type cannot protect a String merely by being present in the service.
    return converters.stream()
        .anyMatch(
            c ->
                c.isAssignableTo(CONVERTER)
                    && c.tryGetAnnotationOfType("jakarta.persistence.Converter")
                        .flatMap(a -> a.get("autoApply"))
                        .filter(Boolean.TRUE::equals)
                        .isPresent());
  }
}
