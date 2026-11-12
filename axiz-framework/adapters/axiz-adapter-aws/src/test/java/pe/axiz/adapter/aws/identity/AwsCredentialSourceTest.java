package pe.axiz.adapter.aws.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.utils.SdkAutoCloseable;

class AwsCredentialSourceTest {
  @Test
  void ownedAndBorrowedResourcesCloseUniformlyWithoutExposingTheOwner() {
    var closes = new AtomicInteger();
    var owner =
        AwsCredentialSource.owned(
            () -> AwsBasicCredentials.create("identity", "fixture"), closes::incrementAndGet);
    var view = owner.provider();
    assertFalse(view instanceof SdkAutoCloseable);
    var first = AwsCredentialSource.borrowed(view);
    var second = AwsCredentialSource.borrowed(view);
    assertEquals("identity", first.provider().resolveCredentials().accessKeyId());
    first.close();
    first.close();
    assertThrows(SdkClientException.class, () -> first.provider().resolveCredentials());
    assertEquals(0, closes.get());
    assertEquals("identity", second.provider().resolveCredentials().accessKeyId());
    owner.close();
    owner.close();
    assertEquals(1, closes.get());
    assertThrows(SdkClientException.class, () -> second.provider().resolveCredentials());
    second.close();
    assertEquals(1, closes.get());
  }

  @Test
  void cleanupFailureDoesNotPermitASecondCloseOrAStaleCredentialRead() {
    var closes = new AtomicInteger();
    var source =
        AwsCredentialSource.owned(
            () -> AwsBasicCredentials.create("identity", "fixture"),
            () -> {
              closes.incrementAndGet();
              throw new IllegalStateException("fixture cleanup");
            });
    assertThrows(IllegalStateException.class, source::close);
    source.close();
    assertEquals(1, closes.get());
    assertThrows(SdkClientException.class, () -> source.provider().resolveCredentials());
  }
}
