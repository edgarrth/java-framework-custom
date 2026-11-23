package pe.axiz.adapter.aws.transport;

import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelOption;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.xml.stream.XMLInputFactory;
import org.jspecify.annotations.Nullable;
import pe.axiz.adapter.aws.identity.AwsCredentialSource;
import pe.axiz.adapter.aws.identity.AwsCredentialSources;
import pe.axiz.adapter.aws.identity.WarmedAwsCredentials;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.awscore.client.builder.AwsAsyncClientBuilder;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.client.config.SdkAdvancedAsyncClientOption;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.http.TlsTrustManagersProvider;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.utils.SdkAutoCloseable;

/**
 * Recursos propios de transporte no bloqueante y de finalización; se construyen durante el arranque
 * y se cierran después de los clientes.
 */
public final class AsyncAwsClients implements SdkAutoCloseable {
  private final WarmedAwsCredentials credentials;
  private final AsyncAwsTransportSettings transportSettings;
  private final BoundedCompletionExecutor completions;
  private CompletionObserver observer = CompletionObserver.NOOP;
  private boolean closed;

  public AsyncAwsClients() {
    this(
        AwsCredentialSources.create(TransportSecurityPolicy.system()),
        Clock.systemUTC(),
        Duration.ofSeconds(15),
        AsyncAwsTransportSettings.defaults());
  }

  /**
   * Toma posesión de la fuente de credenciales suministrada y la precalienta antes de cualquier
   * llamada asíncrona nativa.
   */
  public AsyncAwsClients(
      AwsCredentialSource source,
      Clock clock,
      Duration refreshInterval,
      AsyncAwsTransportSettings transportSettings) {
    this(source, clock, refreshInterval, 2, 256, transportSettings);
  }

  /**
   * Es dueño de las credenciales, de la ejecución acotada de callbacks y del observador asociado.
   */
  public AsyncAwsClients(
      AwsCredentialSource source,
      Clock clock,
      Duration refreshInterval,
      int workers,
      int queueCapacity,
      AsyncAwsTransportSettings transportSettings) {
    try {
      this.transportSettings = Objects.requireNonNull(transportSettings);
      BoundedCompletionExecutor.validWorkers(workers, queueCapacity);
    } catch (RuntimeException | Error failure) {
      source.close();
      throw failure;
    }
    credentials = new WarmedAwsCredentials(source, clock, refreshInterval);
    try {
      completions = new BoundedCompletionExecutor(workers, queueCapacity);
    } catch (RuntimeException | Error failure) {
      credentials.close();
      throw failure;
    }
  }

  /** Reemplaza un enlace de instrumentación y libera su registro de forma determinista. */
  public synchronized void observeCompletions(
      Function<CompletionPool, CompletionObserver> binding) {
    if (closed) throw new IllegalStateException("AWS resources are closed");
    observer.close();
    observer = CompletionObserver.NOOP;
    completions.observe(observer);
    observer =
        Objects.requireNonNull(
            binding.apply(
                new CompletionPool() {
                  @Override
                  public int getActiveCount() {
                    return completions.getActiveCount();
                  }

                  @Override
                  public int getQueueSize() {
                    return completions.getQueueSize();
                  }
                }));
    completions.observe(observer);
  }

  /**
   * Credenciales de solo instantánea y sin propiedad para firmantes auxiliares; este propietario
   * controla la renovación.
   */
  @SuppressWarnings("UnnecessaryMethodReference") // La vista de interfaz no debe exponer close().
  public AwsCredentialsProvider credentialsProvider() {
    return credentials::resolveCredentials;
  }

  public <B extends AwsClientBuilder<B, C> & AwsAsyncClientBuilder<B, C>, C> C configured(
      B builder, Function<String, @Nullable String> properties, String service) {
    return configured(builder, properties, service, null);
  }

  public <B extends AwsClientBuilder<B, C> & AwsAsyncClientBuilder<B, C>, C> C configured(
      B builder,
      Function<String, @Nullable String> properties,
      String service,
      @Nullable Duration apiCallTimeout) {
    if (apiCallTimeout != null && !apiCallTimeout.isPositive())
      throw new IllegalArgumentException("apiCallTimeout must be positive");
    // El SDK crea parsers XML por hilo en los workers de red. Se descubre el proveedor y se cargan
    // los archivos de política XML del JDK en este punto, al configurar el cliente en el hilo de
    // arranque.
    var xmlFactory = XMLInputFactory.newFactory();
    xmlFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    xmlFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    var settings =
        AwsClientSettings.resolve(properties, service, AwsClientSettings.RegionPolicy.EXPLICIT);
    // EXPLICIT siempre resuelve una región antes de configurar el transporte no bloqueante.
    builder
        .region(Objects.requireNonNull(settings.region()))
        .credentialsProvider(credentialsProvider());
    if (settings.endpoint() != null) builder.endpointOverride(settings.endpoint());
    AwsTransportSecurity.apply(builder, settings.transportSecurity());
    builder.httpClientBuilder(
        NettyNioAsyncHttpClient.builder()
            .maxConcurrency(64)
            // El pooling adaptativo detiene los event loops mientras expande los magazines. En su
            // lugar se usan chunks independientes; la demanda y la concurrencia del transporte
            // acotan los buffers vivos.
            .putChannelOption(ChannelOption.ALLOCATOR, new UnpooledByteBufAllocator(true))
            .useNonBlockingDnsResolver(true)
            .tlsTrustManagersProvider(warmedTrustManagers())
            .connectionTimeout(Duration.ofSeconds(3))
            .connectionAcquisitionTimeout(Duration.ofSeconds(5))
            .tlsNegotiationTimeout(Duration.ofSeconds(5))
            .connectionTimeToLive(transportSettings.connectionTtl())
            .connectionMaxIdleTime(transportSettings.maxIdleTime())
            .useIdleConnectionReaper(true)
            .readTimeout(Duration.ofSeconds(30))
            .writeTimeout(Duration.ofSeconds(30)));
    builder.asyncConfiguration(
        config ->
            config.advancedOption(
                SdkAdvancedAsyncClientOption.FUTURE_COMPLETION_EXECUTOR, completions));
    var existing = builder.overrideConfiguration();
    var overrides =
        (existing == null ? ClientOverrideConfiguration.builder() : existing.toBuilder())
            .apiCallAttemptTimeout(Duration.ofSeconds(30))
            .apiCallTimeout(Duration.ofSeconds(60));
    if (apiCallTimeout != null)
      overrides
          .apiCallTimeout(apiCallTimeout)
          .apiCallAttemptTimeout(
              apiCallTimeout.compareTo(Duration.ofSeconds(30)) < 0
                  ? apiCallTimeout
                  : Duration.ofSeconds(30));
    if (Set.of("dynamodb", "kms", "secretsmanager", "ssm", "sqs", "sns").contains(service)) {
      // Estos servicios serializan payloads finitos en memoria. El firmante SigV4 estándar del SDK
      // usa SHA-256 por hilo en lugar del pool de digest compartido y bloqueante del firmante nuevo
      // (AWS #6590). S3 en streaming se mantiene con su firmante nativo; nunca se desactiva la
      // firma del payload ni se usa reflexión.
      useFinitePayloadSigner(overrides);
    }
    builder.overrideConfiguration(overrides.build());
    return builder.build();
  }

  // El firmante de reemplazo adquiere un lock de pool de digest compartido (AWS SDK #6590). La
  // opción soportada por el SDK se restringe a servicios con payload finito hasta que el SDK
  // corrija esa ruta.
  @SuppressWarnings("deprecation")
  private static void useFinitePayloadSigner(ClientOverrideConfiguration.Builder overrides) {
    overrides.putAdvancedOption(SdkAdvancedClientOption.SIGNER, Aws4Signer.create());
  }

  private static TlsTrustManagersProvider warmedTrustManagers() {
    try {
      var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      factory.init((KeyStore) null);
      TrustManager[] snapshot = factory.getTrustManagers();
      return () -> snapshot.clone();
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException(
          "Cannot initialize AWS TLS trust managers at startup", failure);
    }
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    completions.shutdown();
    try {
      credentials.close();
    } finally {
      observer.close();
    }
  }
}
