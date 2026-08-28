package com.gustler.backend.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * 기본값이 꺼짐이다. 서비스키가 아직 없어 켜두면 안 풀린 자리표시자를 키 삼아 상류를 두드리고,
 * 통합 테스트가 컨텍스트를 띄우는 내내 15초마다 실호출이 나간다.
 */
@IntegrationTest
class ScheduledCollectionDisabledTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void 수집을_꺼두면_주기_작업을_아예_안_건다() {
        // when
        final int actual = context.getBeanNamesForType(ScheduledTaskHolder.class).length;

        // then
        assertThat(actual).isZero();
    }
}
