package com.gustler.backend.api.account.token;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/**
 * 로그인만 켜고 다른 기능이 꺼져 있어도 만료 계통 정리 작업이 실제로 등록된다.
 *
 * <p>테스트 설정은 정리 주기를 끄므로 여기서만 운영 기본 주기({@link RefreshTokenPurge#DEFAULT_CRON})로 켠다.
 */
@SpringBootTest(properties = {
    "auth.enabled=true",
    "auth.token.purge-cron=" + RefreshTokenPurge.DEFAULT_CRON,
})
@Import(PostgresTestContainer.class)
class RefreshTokenPurgeSchedulingTest {
    @Autowired
    private ScheduledAnnotationBeanPostProcessor scheduler;

    @Test
    void 정리_작업이_스케줄에_등록된다() {
        // when & then
        assertThat(scheduler.getScheduledTasks())
            .map(task -> task.getTask().toString())
            .anyMatch(name -> name.endsWith(RefreshTokenPurge.class.getName() + ".purge"));
    }
}
