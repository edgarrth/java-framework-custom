package pe.axiz.quality.containers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;

class PatchedKafkaIT {
  @Test
  @Timeout(120)
  void patchedBrokerPublishesAndConsumesThroughBothUpdatedCompressionLibraries() throws Exception {
    boolean available = DockerClientFactory.instance().isDockerAvailable();
    if (Boolean.getBoolean("axiz.it.requireDocker")) assertTrue(available, "Docker is required");
    Assumptions.assumeTrue(available);
    try (var kafka = TestContainerImages.kafka()) {
      kafka.start();
      assertTrue(kafka.getDockerImageName().startsWith("axiz-test/kafka:"));
      run(
          kafka,
          "/opt/kafka/bin/kafka-topics.sh",
          "--bootstrap-server",
          "localhost:9093",
          "--create",
          "--topic",
          "patched-codecs",
          "--partitions",
          "1",
          "--replication-factor",
          "1");
      for (String codec : List.of("snappy", "lz4")) {
        run(
            kafka,
            "bash",
            "-c",
            "printf '%s\\n' \"$1\" | /opt/kafka/bin/kafka-console-producer.sh"
                + " --bootstrap-server localhost:9093 --topic patched-codecs"
                + " --command-property \"compression.type=$2\"",
            "--",
            codec + "-value",
            codec);
      }
      String values =
          run(
              kafka,
              "/opt/kafka/bin/kafka-console-consumer.sh",
              "--bootstrap-server",
              "localhost:9093",
              "--topic",
              "patched-codecs",
              "--from-beginning",
              "--max-messages",
              "2",
              "--timeout-ms",
              "15000");
      assertEquals(Set.of("snappy-value", "lz4-value"), values.lines().collect(Collectors.toSet()));
    }
  }

  private static String run(KafkaContainer kafka, String... command) throws Exception {
    var result = kafka.execInContainer(command);
    assertEquals(0, result.getExitCode(), result.getStderr());
    return result.getStdout();
  }
}
