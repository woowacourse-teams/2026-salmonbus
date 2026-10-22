package com.gustler.backend.api.account.unlink;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.member.api.ProviderUnlinks;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

/** 카카오 연결 해제 설정이 기동 때 어떻게 검증되는지 확인한다. 웹 서버나 DB 는 띄우지 않는다. */
class UnlinkConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(UnlinkConfiguration.class)
        .withBean(ProviderUnlinks.class, () -> Mockito.mock(ProviderUnlinks.class))
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withPropertyValues(
            "auth.enabled=true",
            "auth.kakao.enabled=true",
            "auth.kakao.unlink-uri=http://127.0.0.1:9/v1/user/unlink");

    @Test
    void 카카오를_켰는데_어드민_키가_비어_있으면_빠진_환경_변수를_알려_주며_기동에_실패한다() {
        // when & then
        runner.withPropertyValues("auth.kakao.admin-key=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(causeMessages(context.getStartupFailure())).contains("AUTH_KAKAO_ADMIN_KEY");
        });
    }

    @Test
    void 카카오를_켜고_어드민_키가_있으면_해제_클라이언트를_등록한다() {
        // when & then
        runner.withPropertyValues("auth.kakao.admin-key=test-only-kakao-admin-key").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(KakaoUnlinkClient.class);
        });
    }

    private static String causeMessages(Throwable failure) {
        return Stream.iterate(failure, Objects::nonNull, Throwable::getCause)
            .map(Throwable::getMessage)
            .filter(Objects::nonNull)
            .collect(Collectors.joining("\n"));
    }
}
