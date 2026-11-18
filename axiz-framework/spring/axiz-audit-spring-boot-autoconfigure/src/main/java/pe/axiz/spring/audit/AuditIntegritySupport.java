package pe.axiz.spring.audit;

import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.env.Environment;
import pe.axiz.core.audit.AuditChainState;
import pe.axiz.core.audit.AuditSealCalculator;
import pe.axiz.core.audit.AuditSealer;
import pe.axiz.core.audit.ChainCheckpoint;
import pe.axiz.core.audit.InMemoryAuditChainState;
import pe.axiz.core.secrets.SecretResolver;

/** Owns the instance identity and a bounded HMAC snapshot, resolved only during startup. */
final class AuditIntegritySupport implements AutoCloseable {
  private final AxizAuditProperties.Integrity properties;
  private final String service;
  private final String instance;
  private final InMemoryAuditChainState memory;
  private final Set<String> memoryChains = new LinkedHashSet<>();
  private final byte @Nullable [] key;
  private boolean closed;

  AuditIntegritySupport(
      AxizAuditProperties.Integrity properties,
      Environment environment,
      BeanFactory beans,
      Clock clock) {
    this.properties = properties;
    if (properties.mode() == AuditSealer.Mode.NONE && !AuditModeSelection.development(environment))
      throw new IllegalStateException("Audit integrity NONE is restricted to local/test/ci");
    String service = environment.getProperty("spring.application.name", "application");
    if (!service.matches("[A-Za-z0-9._-]{1,128}"))
      throw new IllegalArgumentException(
          "Audit service name must be 1..128 safe identifier characters");
    this.service = service;
    this.instance = UUID.randomUUID().toString();
    this.memory = new InMemoryAuditChainState(clock, 16);
    byte[] resolved = null;
    if (properties.mode() == AuditSealer.Mode.HMAC) {
      try (var secret =
          beans
              .getBean(SecretResolver.class)
              .require(Objects.requireNonNull(properties.hmacSecret()))) {
        resolved = secret.base64Bytes(32, 1024);
      }
    }
    this.key = resolved;
  }

  String chain(String lane) {
    return service + "/" + lane + "/" + instance;
  }

  synchronized AuditSealer memory(String lane) {
    if (enabled()) {
      String id = chain(lane);
      if (!memoryChains.contains(id) && memoryChains.size() >= 16)
        throw new IllegalStateException("Audit memory chain capacity reached");
      memoryChains.add(id);
    }
    return sealer(lane, memory);
  }

  synchronized boolean hasMemoryChains() {
    return !memoryChains.isEmpty();
  }

  /** Unconfirmed snapshots: only strict delivery can turn these into trusted checkpoints. */
  List<ChainCheckpoint> memoryCheckpoints() {
    List<String> ids;
    synchronized (this) {
      ids = List.copyOf(memoryChains);
    }
    return ids.stream().map(memory::checkpoint).filter(head -> head.sequence() > 0).toList();
  }

  AuditSealer sealer(String lane, AuditChainState state) {
    return new AuditSealer(
        chain(lane),
        properties.mode(),
        state,
        key == null ? null : this::keyCopy,
        properties.hmacKeyId());
  }

  AuditSealCalculator calculator() {
    return new AuditSealCalculator(
        properties.mode(), key == null ? null : this::keyCopy, properties.hmacKeyId());
  }

  boolean enabled() {
    return properties.mode() != AuditSealer.Mode.NONE;
  }

  private synchronized byte[] keyCopy() {
    if (closed || key == null) throw new IllegalStateException("Audit HMAC key is unavailable");
    return key.clone();
  }

  @Override
  public synchronized void close() {
    if (key != null) Arrays.fill(key, (byte) 0);
    closed = true;
  }
}
