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
                .isEqualTo("select /* salmonbus:api.board */ id from route where id = ?");
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "private-route?secret=key");
            assertThat(new ApiSqlOrigin().inspect("select 1")).isEqualTo("select /* salmonbus:api.other */ 1");
        } finally { RequestContextHolder.resetRequestAttributes(); }
    }

    @Test void 앞쪽_주석과_문자열_리터럴을_보존한다() {
        assertThat(new ApiSqlOrigin().inspect("/* hibernate */\n-- read\nSELECT 'select secret'"))
            .isEqualTo("/* hibernate */\n-- read\nSELECT /* salmonbus:api.other */ 'select secret'");
        assertThat(new ApiSqlOrigin().inspect("with x as (select 1) select * from x"))
            .isEqualTo("with /* salmonbus:api.other */ x as (select 1) select * from x");
        assertThat(new ApiSqlOrigin().inspect("show application_name")).isEqualTo("show application_name");
    }
}
