package com.gustler.backend.api.http.login;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

/** 쿠키를 저장해 다시 보내는 브라우저. 리다이렉트는 따라가지 않고 응답 그대로 돌려준다. */
final class LoginBrowser {
    private static final String CSRF_COOKIE = "__Host-XSRF-TOKEN";

    private final int port;
    private final HttpClient client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    private final Map<String, String> cookies = new LinkedHashMap<>();

    LoginBrowser(int port) {
        this.port = port;
    }

    HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return send(request(path).GET());
    }

    HttpResponse<String> post(String path) throws IOException, InterruptedException {
        return send(request(path)
            .header("X-XSRF-TOKEN", cookies.getOrDefault(CSRF_COOKIE, ""))
            .POST(HttpRequest.BodyPublishers.noBody()));
    }

    /** CSRF 쿠키를 헤더로 되돌려 보내는 JSON POST. 쿠키가 없으면 먼저 {@code /api/auth/session} 으로 받는다. */
    HttpResponse<String> postJson(String path, String json) throws IOException, InterruptedException {
        if (!cookies.containsKey(CSRF_COOKIE)) {
            get("/api/auth/session");
        }
        return send(request(path)
            .header("X-XSRF-TOKEN", cookies.getOrDefault(CSRF_COOKIE, ""))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    /** {@link #postJson} 과 같되 {@code Origin} 헤더를 붙인다. */
    HttpResponse<String> postJsonFrom(String origin, String path, String json)
        throws IOException, InterruptedException {
        if (!cookies.containsKey(CSRF_COOKIE)) {
            get("/api/auth/session");
        }
        return send(request(path)
            .header("X-XSRF-TOKEN", cookies.getOrDefault(CSRF_COOKIE, ""))
            .header("Origin", origin)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    /** CSRF 헤더 없이 보내는 JSON POST. 다른 사이트의 요청을 흉내 낸다. */
    HttpResponse<String> postJsonWithoutCsrf(String path, String json) throws IOException, InterruptedException {
        return send(request(path)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    static String location(HttpResponse<String> response) {
        return header(response, "Location");
    }

    static String header(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name).orElse("");
    }

    static MultiValueMap<String, String> queryOf(String authorizationLocation) {
        return UriComponentsBuilder.fromUriString(authorizationLocation).build().getQueryParams();
    }

    static String stateOf(String authorizationLocation) {
        return queryOf(authorizationLocation).getFirst("state");
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (!cookies.isEmpty()) {
            builder.header("Cookie", String.join("; ", cookies.entrySet().stream()
                .map(cookie -> cookie.getKey() + "=" + cookie.getValue()).toList()));
        }
        return builder;
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        absorb(response);
        return response;
    }

    private void absorb(HttpResponse<String> response) {
        List<String> setCookies = response.headers().allValues("Set-Cookie");
        for (String setCookie : setCookies) {
            String[] parts = setCookie.split(";", 2);
            String[] pair = parts[0].split("=", 2);
            final boolean expired = parts.length > 1 && parts[1].toLowerCase().contains("max-age=0");
            if (expired || pair.length < 2 || pair[1].isEmpty()) {
                cookies.remove(pair[0]);
                continue;
            }
            cookies.put(pair[0], pair[1]);
        }
    }
}
