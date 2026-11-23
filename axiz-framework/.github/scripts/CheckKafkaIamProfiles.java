import java.nio.file.Path;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

/**
 * Enlace efectivo de Spring de la configuración main/local entregada, sin arrancar Kafka ni
 * contactar a AWS.
 */
class CheckKafkaIamProfiles {
  public static void main(String[] args) throws Exception {
    Path project = Path.of(args[0]);
    var loader = new YamlPropertySourceLoader();
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    var main =
        loader.load(
            "main", new FileSystemResource(project.resolve("src/main/resources/application.yaml")));
    for (var source : main) environment.getPropertySources().addLast(source);
    String key = "axiz.messaging.kafka.iam";
    if (!environment.containsProperty(key)) return;
    if (!Binder.get(environment).bind(key, Boolean.class).orElse(false))
      throw new AssertionError(
          "Kafka IAM must default to true without environment variables: " + project);
    var local =
        loader.load(
            "local",
            new FileSystemResource(project.resolve("src/main/resources/application-local.yaml")));
    for (var source : local) environment.getPropertySources().addFirst(source);
    if (Binder.get(environment).bind(key, Boolean.class).orElse(true))
      throw new AssertionError("Local Kafka must explicitly disable IAM: " + project);
    System.out.println("PASS Kafka IAM default=true, local=false: " + project.getFileName());
  }
}
