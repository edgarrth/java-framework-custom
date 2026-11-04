package pe.axiz.payments.notifications.archive;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import pe.axiz.core.commons.json.JsonReader;
import pe.axiz.core.commons.json.JsonWriter;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.lock.LockTemplate;
import pe.axiz.core.storage.ObjectMetadata;
import pe.axiz.core.storage.ObjectStorage;
import pe.axiz.core.storage.StoredObject;
import pe.axiz.payments.notifications.event.AuditRecord;

/**
 * Archives audit records on S3: one object per record under {@code audit/yyyy/MM/dd/<service>/}
 * (writing the same record twice is harmless) and a daily manifest with counts per service. The
 * manifest is a read-modify-write, so replicas serialize it with a distributed lock (DynamoDB).
 */
@Component
public class AuditArchive {

  static final String PREFIX = "audit/";
  static final Duration LEASE = Duration.ofSeconds(10);
  static final Duration WAIT = Duration.ofSeconds(5);

  private final ObjectStorage storage;
  private final LockTemplate locks;

  public AuditArchive(ObjectStorage storage, LockTemplate locks) {
    this.storage = storage;
    this.locks = locks;
  }

  /** Stores the record; returns the object key. */
  public String store(AuditRecord r) {
    LocalDate day = Instant.parse(r.occurredAt()).atZone(ZoneOffset.UTC).toLocalDate();
    String key = dayPrefix(day) + safe(r.service()) + "/" + safe(r.id()) + ".json";
    boolean isNew = storage.head(key).isEmpty();
    put(key, JsonWriter.write(toMap(r)));
    if (isNew) {
      count(day, r.service());
    }
    return key;
  }

  static String dayPrefix(LocalDate day) {
    return PREFIX
        + day.getYear()
        + "/"
        + "%02d".formatted(day.getMonthValue())
        + "/"
        + "%02d".formatted(day.getDayOfMonth())
        + "/";
  }

  private void count(LocalDate day, String service) {
    String manifest = dayPrefix(day) + "_manifest.json";
    try {
      locks.withLock(
          "audit-manifest:" + day,
          LEASE,
          WAIT,
          lease -> {
            Map<String, @Nullable Object> counts =
                storage.get(manifest).map(AuditArchive::read).orElseGet(LinkedHashMap::new);
            Object current = counts.get(service);
            long n = current instanceof Number num ? num.longValue() : 0;
            counts.put(service, n + 1);
            lease.ensureHeld(); // another replica may own the manifest once the lease is lost
            put(manifest, JsonWriter.write(counts));
            return null;
          });
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new AxizException(CoreErrors.UNAVAILABLE, "manifest lock: " + e);
    }
  }

  private static Map<String, @Nullable Object> read(StoredObject object) {
    try (var in = object.content()) {
      return new LinkedHashMap<>(
          JsonReader.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void put(String key, String json) {
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    storage.put(
        key, new ByteArrayInputStream(bytes), ObjectMetadata.of("application/json", bytes.length));
  }

  private static Map<String, @Nullable Object> toMap(AuditRecord r) {
    Map<String, @Nullable Object> m = new LinkedHashMap<>();
    m.put("id", r.id());
    m.put("occurredAt", r.occurredAt());
    m.put("service", r.service());
    m.put("action", r.action());
    m.put("resourceType", r.resourceType());
    m.put("resourceId", r.resourceId());
    m.put("outcome", r.outcome());
    m.put("details", r.details());
    return m;
  }

  /** Keys only take safe characters from the record. */
  static String safe(String value) {
    return value.replaceAll("[^A-Za-z0-9._-]", "_");
  }
}
