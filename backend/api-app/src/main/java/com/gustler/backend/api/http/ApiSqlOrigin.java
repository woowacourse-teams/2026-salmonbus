package com.gustler.backend.api.http;

import java.util.Map;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

/** 고정된 업무 이름만 SQL 주석에 붙인다. SQL 로그나 요청별 추가 DB 호출은 없다. */
@Component
@Profile("observability")
public class ApiSqlOrigin implements HibernatePropertiesCustomizer, StatementInspector {
    @Override public void customize(Map<String, Object> properties) {
        properties.put("hibernate.session_factory.statement_inspector", this);
    }

    @Override public String inspect(String sql) {
        Object pattern = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a
            ? a.getRequest().getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) : null;
        String origin = switch (pattern == null ? "" : pattern.toString()) {
            case "/api/v1/routes/{routeId}/board" -> "api.board";
            case "/api/v1/routes/{routeId}/vehicles" -> "api.vehicles";
            case "/api/v1/routes" -> "api.routes";
            default -> "api.other";
        };
        return "/* salmonbus:" + origin + " */ " + sql;
    }
}
