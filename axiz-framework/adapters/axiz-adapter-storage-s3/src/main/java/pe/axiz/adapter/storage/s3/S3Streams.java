package pe.axiz.adapter.storage.s3;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import software.amazon.awssdk.core.async.AsyncRequestBody;

/** SDK protocol bridges stay private to the technology boundary. */
final class S3Streams {
  private S3Streams() {}

  private static ByteBuffer owned(ByteBuffer chunk) {
    ByteBuffer copy = ByteBuffer.allocate(chunk.remaining());
    copy.put(chunk.duplicate()).flip();
    return copy.asReadOnlyBuffer();
  }

  static final class Upload implements AsyncRequestBody, AutoCloseable {
    private final Flow.Publisher<ByteBuffer> content;
    private final long contentLength;
    private final Set<Flow.Subscription> subscriptions = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    Upload(Flow.Publisher<ByteBuffer> content, long contentLength) {
      this.content = Objects.requireNonNull(content);
      this.contentLength = contentLength;
    }

    @Override
    public Optional<Long> contentLength() {
      return Optional.of(contentLength);
    }

    @Override
    public void subscribe(Subscriber<? super ByteBuffer> target) {
      Objects.requireNonNull(target);
      content.subscribe(
          new Flow.Subscriber<>() {
            private Flow.@Nullable Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription incoming) {
              if (subscription != null) {
                incoming.cancel();
                return;
              }
              subscription = incoming;
              subscriptions.add(incoming);
              target.onSubscribe(
                  new Subscription() {
                    @Override
                    public void request(long n) {
                      if (!closed.get()) incoming.request(n);
                    }

                    @Override
                    public void cancel() {
                      subscriptions.remove(incoming);
                      incoming.cancel();
                    }
                  });
              if (closed.get()) {
                subscriptions.remove(incoming);
                incoming.cancel();
              }
            }

            @Override
            public void onNext(ByteBuffer chunk) {
              if (!closed.get()) target.onNext(owned(chunk));
            }

            @Override
            public void onError(Throwable failure) {
              if (subscription != null) subscriptions.remove(subscription);
              if (!closed.get()) target.onError(failure);
            }

            @Override
            public void onComplete() {
              if (subscription != null) subscriptions.remove(subscription);
              if (!closed.get()) target.onComplete();
            }
          });
    }

    @Override
    public void close() {
      closed.set(true);
      subscriptions.forEach(Flow.Subscription::cancel);
      subscriptions.clear();
    }
  }

  static final class Download implements Flow.Publisher<ByteBuffer>, Runnable {
    private final Publisher<ByteBuffer> source;
    private final AtomicBoolean subscribed = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<@Nullable Subscription> subscription = new AtomicReference<>();

    Download(Publisher<ByteBuffer> source) {
      this.source = source;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super ByteBuffer> target) {
      Objects.requireNonNull(target);
      if (!subscribed.compareAndSet(false, true)) {
        target.onSubscribe(
            new Flow.Subscription() {
              @Override
              public void request(long n) {}

              @Override
              public void cancel() {}
            });
        target.onError(
            new IllegalStateException(
                "Download permits one subscription and is closed after abort"));
        return;
      }
      source.subscribe(
          new Subscriber<>() {
            @Override
            public void onSubscribe(Subscription incoming) {
              if (!subscription.compareAndSet(null, incoming)) {
                incoming.cancel();
                return;
              }
              target.onSubscribe(
                  new Flow.Subscription() {
                    @Override
                    public void request(long n) {
                      if (!closed.get()) incoming.request(n);
                    }

                    @Override
                    public void cancel() {
                      run();
                    }
                  });
              if (closed.get()) incoming.cancel();
            }

            @Override
            public void onNext(ByteBuffer chunk) {
              if (!closed.get()) target.onNext(owned(chunk));
            }

            @Override
            public void onError(Throwable failure) {
              if (closed.compareAndSet(false, true)) target.onError(failure);
            }

            @Override
            public void onComplete() {
              if (closed.compareAndSet(false, true)) target.onComplete();
            }
          });
    }

    @Override
    public void run() {
      closed.set(true);
      Subscription active = subscription.get();
      if (active != null) active.cancel();
      // A response whose headers were consumed but whose body was never subscribed still owns a
      // socket.
      if (subscribed.compareAndSet(false, true))
        source.subscribe(
            new Subscriber<>() {
              @Override
              public void onSubscribe(Subscription incoming) {
                incoming.cancel();
              }

              @Override
              public void onNext(ByteBuffer ignored) {}

              @Override
              public void onError(Throwable ignored) {}

              @Override
              public void onComplete() {}
            });
    }
  }
}
