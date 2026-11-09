package com.gustler.backend.routecatalog.infrastructure.supplementary;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** 응답 바이트·전체 시간·리디렉션을 제한한다. 예외 메시지나 인증 URL을 기록하지 않는다. */
final class BoundedJsonHttp {
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @FunctionalInterface
    interface Fetch { Result get(URI uri); }

    record Result(Integer httpStatus, byte[] body) {
        Result { body = body == null ? null : body.clone(); }
        @Override public byte[] body() { return body == null ? null : body.clone(); }
        @Override public String toString() { return "HttpResult[status="+httpStatus+", retainedBody="+(body != null)+"]"; }
    }

    byte[] get(URI uri) {
        Result result = getWithMetadata(uri);
        if (!Integer.valueOf(200).equals(result.httpStatus()) || result.body() == null)
            throw new IllegalStateException("SOURCE_FETCH_FAILED");
        return result.body();
    }

    Result getWithMetadata(URI uri) {
        // Providers select JSON through their query parameters. BMS rejects an explicit JSON Accept header.
        var future = client.sendAsync(HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(6)).GET().build(),
                info -> new LimitedBody());
        try {
            var response = future.get(7, TimeUnit.SECONDS);
            // Error text can echo credentials. It is discarded before crossing this boundary.
            return new Result(response.statusCode(), response.statusCode() == 200 ? response.body() : null);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            future.cancel(true);
            return new Result(null, null);
        }
    }

    static Fetch legacy(java.util.function.Function<URI, byte[]> fetch) {
        return uri -> {
            try { return new Result(200, fetch.apply(uri)); }
            catch (RuntimeException failure) { return new Result(null, null); }
        };
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        public CompletionStage<byte[]> getBody() {
            return result;
        }

        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if ((long) bytes.size() + buffer.remaining() > 8_000_000) {
                    subscription.cancel();
                    result.completeExceptionally(new IllegalStateException("SOURCE_TOO_LARGE"));
                    return;
                }
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }

        public void onError(Throwable failure) {
            result.completeExceptionally(new IllegalStateException("SOURCE_BODY_FAILED"));
        }

        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }
}
