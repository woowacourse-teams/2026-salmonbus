package com.gustler.backend.api.http;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

class ApiSqlOriginTest {
    @Test void SQL과_파라미터를_유지하며_고정된_업무_표식만_추가한다() {
        var request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/routes/{routeId}/board");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            assertThat(new ApiSqlOrigin().inspect("select id from route where id = ?"))
                .isEqualTo("/* salmonbus:api.board */ select id from route where id = ?");
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "private-route?secret=key");
            assertThat(new ApiSqlOrigin().inspect("select 1")).isEqualTo("/* salmonbus:api.other */ select 1");
        } finally { RequestContextHolder.resetRequestAttributes(); }
    }
}
