package pe.axiz.quality.stores;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.dockerjava.api.command.InspectContainerResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslStoreBundle;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.kafka.KafkaContainer;
import pe.axiz.core.commons.net.DataTransportSecurityPolicy;
import pe.axiz.spring.kafka.KafkaConnectionProperties;
import pe.axiz.spring.kafka.KafkaTransportSecurity;

/** Native producer, consumer and admin handshakes using Boot connection details and SSL bundles. */
@Timeout(180)
class KafkaTlsIT {
  @TempDir Path temporary;

  @Test
  void eachClientUsesTheTrustedBundleAndRejectsAnUntrustedCaOrWrongHostname() throws Exception {
    Docker.require();
    var material = TlsMaterial.create(temporary);
    try (var kafka = new TlsKafka()) {
      kafka.withCopyToContainer(
          Transferable.of(
              Files.readString(material.key()) + Files.readString(material.certificate()), 0644),
          "/tmp/axiz-server.pem");
      kafka
          .withEnv("KAFKA_SSL_KEYSTORE_TYPE", "PEM")
          .withEnv("KAFKA_SSL_KEYSTORE_LOCATION", "/tmp/axiz-server.pem");
      kafka.start();
      var trusted = details(kafka, "localhost", material.certificate());
      try (var admin =
          AdminClient.create(properties(trusted, KafkaConnectionProperties.ClientKind.ADMIN))) {
        admin
            .createTopics(List.of(new NewTopic("tls-proof", 1, (short) 1)))
            .all()
            .get(10, TimeUnit.SECONDS);
      }
      try (var producer =
          new KafkaProducer<String, String>(
              properties(trusted, KafkaConnectionProperties.ClientKind.PRODUCER))) {
        assertNotNull(
            producer
                .send(new ProducerRecord<>("tls-proof", "key", "verified"))
                .get(10, TimeUnit.SECONDS));
      }
      try (var consumer =
          new KafkaConsumer<String, String>(
              properties(trusted, KafkaConnectionProperties.ClientKind.CONSUMER))) {
        consumer.assign(List.of(new TopicPartition("tls-proof", 0)));
        consumer.seekToBeginning(consumer.assignment());
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        String actual = null;
        while (actual == null && System.nanoTime() < deadline) {
          var rows = consumer.poll(Duration.ofMillis(250));
          if (!rows.isEmpty()) actual = rows.iterator().next().value();
        }
        assertEquals("verified", actual);
      }
      for (var invalid :
          List.of(
              details(kafka, "localhost", material.untrusted()),
              details(kafka, "127.0.0.1", material.certificate()))) {
        try (var admin =
            AdminClient.create(properties(invalid, KafkaConnectionProperties.ClientKind.ADMIN))) {
          DataStoreTlsIT.assertTlsFailure(
              assertThrows(
                  Exception.class,
                  () -> admin.describeCluster().clusterId().get(10, TimeUnit.SECONDS)));
        }
        try (var producer =
            new KafkaProducer<String, String>(
                properties(invalid, KafkaConnectionProperties.ClientKind.PRODUCER))) {
          DataStoreTlsIT.assertTlsFailure(
              assertThrows(
                  Exception.class,
                  () ->
                      producer
                          .send(new ProducerRecord<>("tls-proof", "key", "rejected"))
                          .get(10, TimeUnit.SECONDS)));
        }
        try (var consumer =
            new KafkaConsumer<String, String>(
                properties(invalid, KafkaConnectionProperties.ClientKind.CONSUMER))) {
          DataStoreTlsIT.assertTlsFailure(
              assertThrows(
                  RuntimeException.class,
                  () -> consumer.partitionsFor("tls-proof", Duration.ofSeconds(5))));
        }
      }
    }
  }

  private static Map<String, Object> properties(
      KafkaConnectionDetails details, KafkaConnectionProperties.ClientKind kind) {
    var environment =
        new MockEnvironment().withProperty("spring.kafka.bootstrap-servers", "ignored.test:1");
    var properties =
        new HashMap<>(KafkaConnectionProperties.properties(environment, details, kind));
    properties.putAll(
        Map.of(
            "request.timeout.ms",
            3000,
            "default.api.timeout.ms",
            5000,
            "max.block.ms",
            5000,
            "key.serializer",
            StringSerializer.class,
            "value.serializer",
            StringSerializer.class,
            "key.deserializer",
            StringDeserializer.class,
            "value.deserializer",
            StringDeserializer.class,
            "group.id",
            "tls-proof"));
    KafkaTransportSecurity.validate(properties, new DataTransportSecurityPolicy("prod", false));
    return properties;
  }

  private static KafkaConnectionDetails details(TlsKafka kafka, String host, Path certificate)
      throws Exception {
    var trust = KeyStore.getInstance(KeyStore.getDefaultType());
    trust.load(null, null);
    try (var input = Files.newInputStream(certificate)) {
      trust.setCertificateEntry(
          "server", CertificateFactory.getInstance("X.509").generateCertificate(input));
    }
    var bundle = SslBundle.of(SslStoreBundle.of(null, null, trust));
    return new KafkaConnectionDetails() {
      @Override
      public List<String> getBootstrapServers() {
        return List.of(host + ":" + kafka.getMappedPort(9095));
      }

      @Override
      public String getSecurityProtocol() {
        return "SSL";
      }

      @Override
      public SslBundle getSslBundle() {
        return bundle;
      }
    };
  }

  private static final class TlsKafka extends KafkaContainer {
    TlsKafka() {
      super("apache/kafka:4.1.0");
      withExposedPorts(9092, 9095);
      // Named TLS listener uses native PEM options; the image's SSL-named shortcut requires JKS
      // credential files.
      withEnv(
          "KAFKA_LISTENERS",
          "PLAINTEXT://0.0.0.0:9092,BROKER://0.0.0.0:9093,CONTROLLER://0.0.0.0:9094,TLS://0.0.0.0:9095");
      withEnv(
          "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
          "BROKER:PLAINTEXT,PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT,TLS:SSL");
    }

    @Override
    protected void containerIsStarting(InspectContainerResponse response) {
      String advertised =
          "PLAINTEXT://"
              + getBootstrapServers()
              + ",BROKER://"
              + response.getConfig().getHostName()
              + ":9093,TLS://localhost:"
              + getMappedPort(9095);
      copyFileToContainer(
          Transferable.of(
              "#!/bin/bash\nexport KAFKA_ADVERTISED_LISTENERS='"
                  + advertised
                  + "'\nexec /etc/kafka/docker/run\n",
              0755),
          "/tmp/testcontainers_start.sh");
    }
  }
}
