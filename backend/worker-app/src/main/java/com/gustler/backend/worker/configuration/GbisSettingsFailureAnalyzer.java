package com.gustler.backend.worker.configuration;

import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.Ordered;

/**
 * GBIS 설정 기동 실패를 인증값을 노출하지 않는 설명과 조치 안내로 바꾼다.
 */
public class GbisSettingsFailureAnalyzer extends AbstractFailureAnalyzer<BindException> implements Ordered {
    private static final ConfigurationPropertyName GBIS = ConfigurationPropertyName.of("gbis");

    private static final String ACTION = "인증키는 GBIS_SERVICE_KEY, GBIS_SERVICE_KEY_<별칭> 또는 GBIS_KEYS_<별칭>=<키> 형식으로 "
        + "한 줄에 하나씩 넣는다. 형식은 RUNBOOK 의 'GBIS 키 여러 개 쓰기'를 본다";

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, BindException cause) {
        if (!GBIS.equals(cause.getName()) && !GBIS.isAncestorOf(cause.getName())) {
            return null;
        }
        return new FailureAnalysis("GBIS 설정(" + cause.getName() + ")을 읽지 못했다. 값은 출력하지 않는다. "
            + reasonOf(cause), ACTION, cause);
    }

    private static String reasonOf(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (isGbisPropertyFailure(current)) {
                return current.getMessage();
            }
        }
        return "값의 형식이 맞지 않는다";
    }

    private static boolean isGbisPropertyFailure(Throwable failure) {
        if (!(failure instanceof IllegalStateException)) {
            return false;
        }
        StackTraceElement[] stackTrace = failure.getStackTrace();
        if (stackTrace.length == 0) {
            return false;
        }
        return stackTrace[0].getClassName().equals(GbisProperties.class.getName());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
