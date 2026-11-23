package pe.axiz.spring.aws;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import pe.axiz.adapter.aws.identity.AwsCredentialSource;
import pe.axiz.adapter.aws.identity.AwsCredentialSources;
import pe.axiz.adapter.aws.identity.AwsCredentialsPlugin;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.utils.SdkAutoCloseable;

/**
 * Identidad propia del contexto: capacidad, servicio, global sin calificar y luego los valores por
 * defecto diferidos del SDK.
 */
public final class AwsIdentity implements SdkAutoCloseable, SmartInitializingSingleton {
  static final String BEAN_NAME = "axizAwsIdentity";
  private final ConfigurableListableBeanFactory beans;
  private final Environment environment;
  private final Set<Target> reported = ConcurrentHashMap.newKeySet();
  private @Nullable AwsCredentialSource defaults;
  private volatile boolean closed;

  AwsIdentity(ConfigurableListableBeanFactory beans, Environment environment) {
    this.beans = Objects.requireNonNull(beans);
    this.environment = Objects.requireNonNull(environment);
  }

  /**
   * Devuelve un recurso cerrable independiente; los proveedores de la aplicación siguen siendo
   * propiedad de Spring.
   */
  public AwsCredentialSource source(AwsService service, AwsCapability capability) {
    if (service == AwsService.UNSPECIFIED || capability == AwsCapability.UNSPECIFIED)
      throw new IllegalArgumentException("An explicit AWS service and capability are required");
    return select(new Target(service, capability), false);
  }

  AwsCredentialSource globalSource() {
    return select(new Target(AwsService.UNSPECIFIED, AwsCapability.UNSPECIFIED), true);
  }

  private AwsCredentialSource select(Target target, boolean globalOnly) {
    checkOpen();
    var service = target.service();
    var capability = target.capability();
    var candidates = candidates(beans);
    List<Candidate> specific =
        globalOnly
            ? List.of()
            : candidates.stream().filter(c -> c.capability == capability).toList();
    List<Candidate> services =
        globalOnly ? List.of() : candidates.stream().filter(c -> c.service == service).toList();
    List<Candidate> globals = candidates.stream().filter(Candidate::global).toList();
    var matches = !specific.isEmpty() ? specific : !services.isEmpty() ? services : globals;
    Candidate chosen = choose(matches);
    AwsCredentialsProvider provider;
    String level;
    String origin;
    if (chosen == null) {
      provider = defaultProvider();
      level = "CONTEXT_DEFAULT";
      origin = "context";
    } else {
      // Una búsqueda diferida seleccionada crea una dependencia real de este gestor. No se recorren
      // beans no relacionados durante la creación y ningún nombre de consumidor forma parte de la
      // API pública.
      if (chosen.owner == beans) beans.registerDependentBean(chosen.name, BEAN_NAME);
      provider = chosen.owner.getBean(chosen.name, AwsCredentialsProvider.class);
      level = !specific.isEmpty() ? "CAPABILITY" : !services.isEmpty() ? "SERVICE" : "GLOBAL";
      origin = chosen.name;
    }
    if (reported.add(target))
      LoggerFactory.getLogger(AwsIdentity.class)
          .info(
              "AWS identity selection service={} capability={} level={} providerBean={}",
              service,
              capability,
              level,
              origin.matches("[A-Za-z_$][A-Za-z0-9_$.-]{0,127}") ? origin : "<redacted-bean-name>");
    AwsCredentialsProvider selected = provider;
    return AwsCredentialSource.borrowed(
        () -> {
          checkOpen();
          return selected.resolveCredentials();
        });
  }

  /**
   * Difiere la adquisición hasta que el callback la necesita; cada invocación posee un recurso
   * separado.
   */
  public Supplier<AwsCredentialSource> factory(AwsService service, AwsCapability capability) {
    checkOpen();
    if (service == AwsService.UNSPECIFIED || capability == AwsCapability.UNSPECIFIED)
      throw new IllegalArgumentException("An explicit AWS service and capability are required");
    var target = new Target(service, capability);
    return () -> source(target.service(), target.capability());
  }

  /** Transfiere un recurso al cliente del SDK sin cambiar el transporte ni la instrumentación. */
  public <B extends AwsClientBuilder<B, C>, C> B configure(
      B builder, AwsService service, AwsCapability capability) {
    var resource = source(service, capability);
    try {
      return builder.addPlugin(new AwsCredentialsPlugin(resource));
    } catch (RuntimeException | Error failure) {
      resource.close();
      throw failure;
    }
  }

  @Override
  public void afterSingletonsInstantiated() {
    // Solo metadatos, incluidos los ámbitos inactivos; nunca se resuelven credenciales ni se
    // instancian proveedores.
    Map<String, List<Candidate>> groups = new HashMap<>();
    for (Candidate candidate : candidates(beans)) {
      String scope =
          candidate.global
              ? "GLOBAL"
              : candidate.service != AwsService.UNSPECIFIED
                  ? "SERVICE:" + candidate.service
                  : "CAPABILITY:" + candidate.capability;
      groups.computeIfAbsent(scope, ignored -> new ArrayList<>()).add(candidate);
    }
    groups.values().forEach(AwsIdentity::choose);
  }

  private static @Nullable Candidate choose(List<Candidate> matches) {
    if (matches.isEmpty()) return null;
    var primary = matches.stream().filter(Candidate::primary).toList();
    if (primary.size() == 1) return primary.getFirst();
    if (matches.size() == 1) return matches.getFirst();
    throw new NoUniqueBeanDefinitionException(
        AwsCredentialsProvider.class, matches.stream().map(Candidate::name).toList());
  }

  private static List<Candidate> candidates(ConfigurableListableBeanFactory owner) {
    List<Candidate> result = new ArrayList<>();
    for (String name : owner.getBeanNamesForType(AwsCredentialsProvider.class, true, false)) {
      var definition =
          owner.containsBeanDefinition(name) ? owner.getMergedBeanDefinition(name) : null;
      if (definition != null && !definition.isAutowireCandidate()) continue;
      var typed = owner.findAnnotationOnBean(name, AwsIdentityFor.class, false);
      boolean qualified = owner.findAnnotationOnBean(name, Qualifier.class, false) != null;
      AwsService service = typed == null ? AwsService.UNSPECIFIED : typed.service();
      AwsCapability capability = typed == null ? AwsCapability.UNSPECIFIED : typed.capability();
      boolean declared = typed != null;
      boolean defaultCandidate = true;
      if (definition instanceof AbstractBeanDefinition metadata) {
        qualified |= !metadata.getQualifiers().isEmpty();
        defaultCandidate = metadata.isDefaultCandidate();
        var scope = metadata.getQualifier(AwsIdentityFor.class.getName());
        if (typed == null && scope != null) {
          declared = true;
          Object serviceValue = scope.getAttribute("service");
          Object capabilityValue = scope.getAttribute("capability");
          if ((serviceValue != null && !(serviceValue instanceof AwsService))
              || (capabilityValue != null && !(capabilityValue instanceof AwsCapability)))
            throw new IllegalStateException("AWS identity scope must use typed enums: " + name);
          if (serviceValue != null) service = (AwsService) serviceValue;
          if (capabilityValue != null) capability = (AwsCapability) capabilityValue;
        }
      }
      if (declared
          && (service == AwsService.UNSPECIFIED) == (capability == AwsCapability.UNSPECIFIED))
        throw new IllegalStateException(
            "AWS identity must declare exactly one service or capability: " + name);
      boolean global = !declared && !qualified && defaultCandidate;
      if (global && definition != null) {
        String factory = definition.getFactoryBeanName();
        Class<?> origin = factory != null ? owner.getType(factory, false) : null;
        if (origin == null && definition.getBeanClassName() != null)
          origin =
              ClassUtils.resolveClassName(
                  definition.getBeanClassName(), owner.getBeanClassLoader());
        if (origin != null
            && MergedAnnotations.from(origin, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY)
                .isPresent(AutoConfiguration.class)) {
          LoggerFactory.getLogger(AwsIdentity.class)
              .warn("Automatic AWS provider rejected as application global identity");
          throw new IllegalStateException(
              "Declare an application AWS credential provider explicitly;"
                  + " an auto-configuration provider is not an application global identity");
        }
      }
      if (!declared && !global) continue;
      if (!owner.isSingleton(name))
        throw new IllegalStateException(
            "AWS credential providers must be singleton beans: " + name);
      result.add(
          new Candidate(
              name,
              owner,
              service,
              capability,
              global,
              definition != null && definition.isPrimary()));
    }
    if (owner.getParentBeanFactory() instanceof ConfigurableListableBeanFactory parent) {
      for (var candidate : candidates(parent))
        if (!owner.containsLocalBean(candidate.name)) result.add(candidate);
    }
    return result;
  }

  private void checkOpen() {
    if (closed) throw SdkClientException.create("AWS identity context is closed");
  }

  private synchronized AwsCredentialsProvider defaultProvider() {
    checkOpen();
    if (defaults == null)
      defaults =
          AwsCredentialSources.create(TransportSecurityPolicy.from(environment::getProperty));
    return defaults.provider();
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    if (defaults != null) defaults.close();
  }

  private record Target(AwsService service, AwsCapability capability) {
    Target {
      Objects.requireNonNull(service);
      Objects.requireNonNull(capability);
    }
  }

  private record Candidate(
      String name,
      ConfigurableListableBeanFactory owner,
      AwsService service,
      AwsCapability capability,
      boolean global,
      boolean primary) {}
}
