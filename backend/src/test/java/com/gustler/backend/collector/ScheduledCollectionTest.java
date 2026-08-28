package com.gustler.backend.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * 돌 노선을 비워둔다. 켜자마자 첫 판이 도는데 노선이 없으면 아무것도 안 해서
 * 이 테스트가 DB 를 건드리지 않는다. 여기서 보는 것은 스케줄이 걸렸느냐 하나다.
 */
@SpringBootTest(properties = "collection.enabled=true")
@Import(PostgresTestContainer.class)
class ScheduledCollectionTest {

    @Autowired
    private ScheduledTaskHolder scheduledTaskHolder;

    @Test
    void 수집을_켜면_적응형_주기_작업이_걸린다() {
        // when
        final int actual = scheduledTaskHolder.getScheduledTasks().size();

        // then
        assertThat(actual).isEqualTo(1);
    }
}
