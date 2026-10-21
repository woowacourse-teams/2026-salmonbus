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
