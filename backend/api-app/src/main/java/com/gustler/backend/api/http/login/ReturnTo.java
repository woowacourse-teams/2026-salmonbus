package com.gustler.backend.api.http.login;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * 로그인을 마치고 돌아갈 사이트 안의 경로를 검증한다.
 *
 * <p>허용하는 값은 {@code /} 와 {@code /routes/{9자리}} 이며, 노선 경로에는 {@code direction} 과
 * {@code stopId} 쿼리만 한 번씩 붙을 수 있다. 그 밖의 값은 모두 {@code /} 로 바꾼다. 허용 목록 밖의 문자를
 * 걸러내는 방식이라 {@code //evil.com}, {@code https://...}, {@code /\evil} 같은 열린 리다이렉트가 통하지 않는다.
 */
final class ReturnTo {
    static final String HOME = "/";

    private static final Pattern ROUTE = Pattern.compile("/routes/\\d{9}");
    private static final Pattern QUERY_PARAMETER = Pattern.compile("direction=(UP|DOWN)|stopId=[A-Za-z0-9_-]{1,40}");

    private ReturnTo() {
    }

    static String sanitize(String candidate) {
        if (candidate == null) {
            return HOME;
        }
        final int queryStart = candidate.indexOf('?');
        if (queryStart < 0) {
            if (isAllowedPath(candidate)) {
                return candidate;
            }
            return HOME;
        }
        String path = candidate.substring(0, queryStart);
        String query = candidate.substring(queryStart + 1);
        if (ROUTE.matcher(path).matches() && hasOnlyAllowedParameters(query)) {
            return candidate;
        }
        return HOME;
    }

    private static boolean isAllowedPath(String path) {
        return HOME.equals(path) || ROUTE.matcher(path).matches();
    }

    private static boolean hasOnlyAllowedParameters(String query) {
        String[] parameters = query.split("&", -1);
        final long distinctNames = Arrays.stream(parameters)
            .map(parameter -> parameter.split("=", 2)[0])
            .distinct()
            .count();
        return distinctNames == parameters.length
            && Arrays.stream(parameters).allMatch(parameter -> QUERY_PARAMETER.matcher(parameter).matches());
    }
}
