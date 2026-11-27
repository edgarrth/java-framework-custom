package pe.axiz.core.secrets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SecretBoundaryTest {
  @Test
  void closingErasesTheOwnedBufferWithoutErasingCopiesOwnedByTheCaller() throws Exception {
    char[] input = {'a', 'b', 'c'};
    var secret = SecretValue.of(input);
    var field = SecretValue.class.getDeclaredField("value");
    field.setAccessible(true);
    char[] owned = (char[]) field.get(secret);
    char[] output = secret.revealChars();
    assertArrayEquals(input, owned);
    secret.close();
    secret.close();
    assertArrayEquals(new char[3], owned);
    assertArrayEquals(input, output);
    assertThrows(IllegalStateException.class, secret::reveal);
  }

  @Test
  void synchronousSourcePriorityFallsThroughOnlyOnAbsenceAndCopiesItsConfiguration() {
    var attempts = new ArrayList<String>();
    try (var value = SecretValue.of("synthetic")) {
      var sources = new ArrayList<SecretResolver>();
      sources.add(
          name -> {
            attempts.add("absent");
            return Optional.empty();
          });
      sources.add(
          name -> {
            attempts.add("found");
            return Optional.of(value);
          });
      sources.add(
          name -> {
            throw new AssertionError("Lower priority source invoked");
          });
      var composite = new CompositeSecretResolver(sources);
      sources.clear();
      assertSame(value, composite.require("requested"));
      assertEquals(List.of("absent", "found"), attempts);
      assertTrue(new CompositeSecretResolver(List.of()).resolve("missing").isEmpty());
      assertThrows(
          SecretNotFoundException.class,
          () -> new CompositeSecretResolver(List.of()).require("missing"));
      var original = new IllegalStateException("unavailable");
      var failed =
          new CompositeSecretResolver(
              List.of(
                  name -> {
                    throw original;
                  },
                  composite));
      assertSame(original, assertThrows(IllegalStateException.class, () -> failed.require("key")));
      assertEquals(List.of("absent", "found"), attempts);
    }
  }

  @Test
  void requiredAsyncResultsCannotBeCancelledAfterSuccessfulOwnershipTransfer() {
    try (var value = SecretValue.of("synthetic")) {
      AsyncSecretResolver source = name -> CompletableFuture.completedFuture(Optional.of(value));
      var result = source.require("key").toCompletableFuture();
      assertSame(value, result.join());
      assertFalse(result.cancel(true));
      assertEquals("synthetic", value.reveal());
    }
  }

  @Test
  void comparisonsIncludeLengthAndEveryCharacterWithoutChangingEitherSecret() {
    for (String left : List.of("", "a", "ab", "a\0", "\0", "áb", "abc")) {
      for (String right : List.of("", "a", "ab", "a\0", "\0", "áb", "abc")) {
        try (var a = SecretValue.of(left);
            var b = SecretValue.of(right)) {
          assertEquals(left.equals(right), a.matches(b));
          assertEquals(left, a.reveal());
          assertEquals(right, b.reveal());
          assertEquals("****", a.masked());
        }
      }
    }
    try (var open = SecretValue.of("a")) {
      var closed = SecretValue.of("a");
      closed.close();
      assertThrows(IllegalStateException.class, () -> open.matches(closed));
      assertThrows(IllegalStateException.class, () -> closed.matches(open));
      assertEquals("a", open.reveal());
    }
  }

  @Test
  void borrowedBuffersArePrivateAndErasedAlsoWhenTheCallbackFails() {
    char[] input = {'a', 'b', 'c'};
    try (var secret = SecretValue.of(input)) {
      Arrays.fill(input, 'x');
      char[] output = secret.revealChars();
      Arrays.fill(output, 'y');
      assertEquals("abc", secret.reveal());
      char[][] retained = new char[1][];
      var failure = new IllegalStateException("callback failed");
      assertSame(
          failure,
          assertThrows(
              IllegalStateException.class,
              () ->
                  secret.use(
                      value -> {
                        retained[0] = value;
                        throw failure;
                      })));
      assertArrayEquals(new char[3], retained[0]);
      assertEquals("abc", secret.reveal());
    }
  }

  @Test
  void base64EnforcesDecodedBoundsEvenWhenEncodedLengthsAreIdentical() {
    for (int length : List.of(1, 2, 3, 31, 32, 33)) {
      byte[] expected = new byte[length];
      Arrays.fill(expected, (byte) 19);
      try (var secret = SecretValue.of(Base64.getEncoder().encodeToString(expected))) {
        assertArrayEquals(expected, secret.base64Bytes(length, length));
        assertThrows(
            IllegalArgumentException.class, () -> secret.base64Bytes(length + 1, length + 1));
        if (length > 1)
          assertThrows(IllegalArgumentException.class, () -> secret.base64Bytes(1, length - 1));
      }
    }
    try (var one = SecretValue.of("AA")) {
      assertArrayEquals(new byte[1], one.base64Bytes(1, 1_048_576));
      assertThrows(IllegalArgumentException.class, () -> one.base64Bytes(1, 1_048_577));
      assertThrows(IllegalArgumentException.class, () -> one.base64Bytes(2, 1));
    }
  }

  @Test
  void compositeRejectsInvalidSourcesAndPreservesCompletionAfterSuccess() {
    AsyncSecretResolver empty = name -> CompletableFuture.completedFuture(Optional.empty());
    assertTrue(
        new CompositeAsyncSecretResolver(Collections.nCopies(64, empty))
            .resolve("missing")
            .toCompletableFuture()
            .join()
            .isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () -> new CompositeAsyncSecretResolver(Collections.nCopies(65, empty)));
    var calls = new AtomicInteger();
    AsyncSecretResolver later =
        name -> {
          calls.incrementAndGet();
          return CompletableFuture.completedFuture(Optional.empty());
        };
    var nullResult =
        new CompositeAsyncSecretResolver(
            List.of(name -> CompletableFuture.completedFuture(null), later));
    assertInstanceOf(
        IllegalStateException.class,
        assertThrows(
                CompletionException.class,
                () -> nullResult.resolve("missing").toCompletableFuture().join())
            .getCause());
    assertEquals(0, calls.get());
    try (var value = SecretValue.of("retained")) {
      var result =
          new CompositeAsyncSecretResolver(
                  List.of(name -> CompletableFuture.completedFuture(Optional.of(value))))
              .resolve("key")
              .toCompletableFuture();
      assertSame(value, result.join().orElseThrow());
      assertFalse(result.cancel(true));
      assertEquals("retained", value.reveal());
    }
  }
}
