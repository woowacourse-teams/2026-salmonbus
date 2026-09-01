package com.gustler.backend;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;

@IntegrationTest
class ApiApplicationTests {

    @Test
    void GBIS_키_없이_컨텍스트가_뜬다() {
    }

    @Test
    void 수집기_클래스는_클래스패스에_아예_없다() {
        assertThatThrownBy(() -> Class.forName("com.gustler.backend.collector.CollectionScheduler"))
            .isInstanceOf(ClassNotFoundException.class);
    }
}
