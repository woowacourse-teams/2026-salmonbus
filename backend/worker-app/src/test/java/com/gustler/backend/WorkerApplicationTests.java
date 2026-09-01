package com.gustler.backend;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;

@IntegrationTest
class WorkerApplicationTests {

    @Test
    void 컨텍스트가_뜬다() {
    }

    @Test
    void 클라이언트_API_클래스는_클래스패스에_아예_없다() {
        assertThatThrownBy(() -> Class.forName("com.gustler.backend.api.route.controller.RouteController"))
            .isInstanceOf(ClassNotFoundException.class);
    }
}
