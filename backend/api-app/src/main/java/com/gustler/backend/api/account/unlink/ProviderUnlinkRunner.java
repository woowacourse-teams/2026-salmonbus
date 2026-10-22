package com.gustler.backend.api.account.unlink;

import com.gustler.backend.member.api.PendingUnlink;
import com.gustler.backend.member.api.ProviderUnlinks;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 잡아 둔 제공자 연결 해제 요청 하나를 한 번 시도하고 결과를 기록한다.
 *
 * <p>탈퇴 요청은 곧바로 이것을 부르고, 실패한 요청은 주기 작업이 다시 부른다. 제공자 호출과 결과 기록 어느 쪽의 예외도 밖으로
 * 내보내지 않는다. 연결 해제에 실패했다고 탈퇴 응답까지 실패하면 안 된다. 결과를 기록하지 못한 요청은 임대가 끝나면 주기
 * 작업이 다시 잡는다. 애플 회원은 토큰을 취소한 뒤에만 지우므로 요청이 생기지 않는다. 서버가 애플 연결을 끊을 수단도 없어,
 * 애플 요청이 오면 실패로 기록한다.
 */
public final class ProviderUnlinkRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProviderUnlinkRunner.class);

    private final ProviderUnlinks unlinks;
    private final ObjectProvider<KakaoUnlinkClient> kakao;
    private final MeterRegistry meters;

    public ProviderUnlinkRunner(
        ProviderUnlinks unlinks,
        ObjectProvider<KakaoUnlinkClient> kakao,
        MeterRegistry meters
    ) {
        this.unlinks = unlinks;
        this.kakao = kakao;
        this.meters = meters;
    }

    public void attempt(PendingUnlink pending) {
        UnlinkResult result = resultOf(pending);
        String provider = pending.identity().provider().name();
        meters.counter("auth.unlink", "provider", provider, "result", tagOf(result)).increment();
        if (!result.unlinked()) {
            LOGGER.warn("제공자 연결 해제에 실패했다. provider={} attempts={} reason={}",
                provider, pending.attempts() + 1, result.reason());
        }
        try {
            record(pending, result);
        } catch (RuntimeException exception) {
            LOGGER.error("연결 해제 결과를 기록하지 못했다. 임대가 끝나면 다시 시도한다. provider={}", provider, exception);
        }
    }

    private void record(PendingUnlink pending, UnlinkResult result) {
        if (result.unlinked()) {
            unlinks.succeeded(pending.id());
            return;
        }
        unlinks.failed(pending.id(), result.reason());
    }

    private static String tagOf(UnlinkResult result) {
        if (result.alreadyUnlinked()) {
            return "not_linked";
        }
        if (result.unlinked()) {
            return "done";
        }
        return "failed";
    }

    private UnlinkResult resultOf(PendingUnlink pending) {
        try {
            return switch (pending.identity().provider()) {
                case KAKAO -> kakao(pending.identity().subject());
                case APPLE -> UnlinkResult.failed("애플은 서버가 다시 해제할 수단이 없다");
            };
        } catch (RuntimeException exception) {
            return UnlinkResult.failed(exception.getClass().getSimpleName());
        }
    }

    private UnlinkResult kakao(String subject) {
        KakaoUnlinkClient client = kakao.getIfAvailable();
        if (client == null) {
            return UnlinkResult.failed("카카오 로그인이 꺼져 있어 해제할 수 없다");
        }
        return client.unlink(subject);
    }
}
