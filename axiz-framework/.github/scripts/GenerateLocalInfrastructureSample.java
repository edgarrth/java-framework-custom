import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;
import pe.axiz.codegen.ProjectGenerator;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;

/** Uses the real generator for an init spanning S3, DynamoDB, SQS, SNS and KMS. */
class GenerateLocalInfrastructureSample {
  public static void main(String[] args) throws Exception {
    var request =
        new ProjectRequest(
            "infra-orders",
            "pe.axiz.samples",
            "pe.axiz.samples.infra",
            "Local resource initialization acceptance",
            "business",
            Structure.HEXAGONAL,
            Stack.SERVLET,
            "dynamodb",
            List.of(
                "storage-s3",
                "crypto",
                "messaging-sqs",
                "messaging-sns",
                "audit-sqs",
                "idempotency-dynamodb"),
            Files.readString(Path.of(args[1])),
            null,
            args[2]);
    var project = ProjectGenerator.withDefaults().generate(request);
    Path output = Path.of(args[0]);
    Files.createDirectories(output);
    Files.writeString(output.resolve("init.sh"), project.text("local/localstack/init.sh"));
    Map<String, Object> compose = new Yaml().load(project.text("compose.yaml"));
    var services = (Map<?, ?>) compose.get("services");
    var localstack = (Map<?, ?>) services.get("localstack");
    Files.writeString(output.resolve("image.txt"), localstack.get("image").toString());
  }
}
