package com.gustler.backend.api.http.docs;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 로그인을 켠 앱이 생성한 명세를 contract/auth-v1.openapi.json 과 대조한다.
 *
 * <p>로그인 컨트롤러는 {@code auth.enabled=true} 일 때만 등록되므로 이 시험에서만 켠다. 설명 문구는 대조하지 않고
 * operation 의 식별자·파라미터·요청 본문·응답 코드·헤더·스키마·예시와 컴포넌트 스키마 전체를 대조한다.
 */
@IntegrationTest
@ActiveProfiles("api-docs-local")
@TestPropertySource(properties = "auth.enabled=true")
class AuthApiDocsContractTest {
    private static final String JSON_CONTENT = "/content/application~1json";
    private static final List<String> SCHEMAS = List.of(
        "AuthSessionResponse", "MemberResponse", "MyProfileResponse", "TokenResponse", "RefreshRequest",
        "UpdateNicknameRequest", "AppleWithdrawalRequest", "AppleWithdrawalResponse", "AppLoginRequest",
        "AppLoginNonceResponse", "AppleAppWithdrawalRequest", "ErrorResponse"
    );

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    private JsonNode generated;
    private JsonNode contract;

    @BeforeEach
    void documents() throws Exception {
        String body = MockMvcBuilders.webAppContextSetup(context).build().perform(get("/api/docs/openapi"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        generated = json.readTree(body);
        contract = json.readTree(Files.readString(Path.of(System.getProperty("auth.openapi.contract"))));
        Files.createDirectories(Path.of("build/reports"));
        Files.writeString(Path.of("build/reports/auth-generated-openapi.json"),
            json.writerWithDefaultPrettyPrinter().writeValueAsString(generated));
    }

    @Test
    void 생성한_문서의_로그인_경로와_메서드가_공유명세와_같다() {
        // when
        Set<String> generatedOperations = authOperations(generated);
        Set<String> contractOperations = authOperations(contract);

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(generatedOperations).isEqualTo(contractOperations);
            softly.assertThat(generatedOperations).hasSize(11);
        }
    }

    @Test
    void 생성한_문서의_로그인_operation이_공유명세와_같다() {
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            for (Entry<String, JsonNode> path : contract.path("paths").properties()) {
                for (Entry<String, JsonNode> method : path.getValue().properties()) {
                    String name = method.getKey() + " " + path.getKey();
                    JsonNode source = method.getValue();
                    JsonNode operation = generated.path("paths").path(path.getKey()).path(method.getKey());

                    softly.assertThat(operation.path("operationId")).as(name).isEqualTo(source.path("operationId"));
                    softly.assertThat(parameterNames(operation)).as(name + " parameters")
                        .isEqualTo(parameterNames(source));
                    softly.assertThat(operation.path("requestBody").path("required")).as(name + " requestBody")
                        .isEqualTo(source.path("requestBody").path("required"));
                    softly.assertThat(operation.at("/requestBody" + JSON_CONTENT + "/schema")).as(name + " requestBody")
                        .isEqualTo(source.at("/requestBody" + JSON_CONTENT + "/schema"));
                    softly.assertThat(operation.path("responses").propertyNames()).as(name + " responses")
                        .containsExactlyInAnyOrderElementsOf(source.path("responses").propertyNames());
                    for (Entry<String, JsonNode> response : source.path("responses").properties()) {
                        assertResponse(softly, name + " " + response.getKey(),
                            operation.path("responses").path(response.getKey()), response.getValue());
                    }
                }
            }
        }
    }

    @Test
    void 생성한_문서의_로그인_스키마가_공유명세와_같다() {
        // given
        JsonNode sourceSchemas = contract.at("/components/schemas");
        JsonNode generatedSchemas = generated.at("/components/schemas");

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(sourceSchemas.propertyNames()).containsExactlyInAnyOrderElementsOf(SCHEMAS);
            for (String name : SCHEMAS) {
                softly.assertThat(generatedSchemas.path(name)).as(name).isEqualTo(sourceSchemas.path(name));
            }
        }
    }

    private static void assertResponse(
        AutoCloseableSoftAssertions softly,
        String name,
        JsonNode generatedResponse,
        JsonNode sourceResponse
    ) {
        softly.assertThat(generatedResponse.path("headers").propertyNames()).as(name + " headers")
            .containsExactlyInAnyOrderElementsOf(sourceResponse.path("headers").propertyNames());
        softly.assertThat(generatedResponse.at(JSON_CONTENT + "/schema")).as(name + " schema")
            .isEqualTo(sourceResponse.at(JSON_CONTENT + "/schema"));
        JsonNode sourceExamples = sourceResponse.at(JSON_CONTENT + "/examples");
        JsonNode generatedExamples = generatedResponse.at(JSON_CONTENT + "/examples");
        softly.assertThat(generatedExamples.propertyNames()).as(name + " examples")
            .containsExactlyInAnyOrderElementsOf(sourceExamples.propertyNames());
        for (Entry<String, JsonNode> example : sourceExamples.properties()) {
            softly.assertThat(generatedExamples.path(example.getKey()).path("value")).as(name + " " + example.getKey())
                .isEqualTo(example.getValue().path("value"));
        }
    }

    private static Set<String> authOperations(JsonNode document) {
        Set<String> operations = new TreeSet<>();
        for (Entry<String, JsonNode> path : document.path("paths").properties()) {
            if (!path.getKey().startsWith("/api/auth/") && !path.getKey().startsWith("/api/me")) {
                continue;
            }
            // /api/me 아래의 즐겨찾기는 인증을 사용하지만 회원 프로필 명세의 소유 경로는 아니다.
            if (path.getKey().startsWith("/api/me/favorite")) {
                continue;
            }
            for (String method : path.getValue().propertyNames()) {
                operations.add(method + " " + path.getKey());
            }
        }
        return operations;
    }

    private static Set<String> parameterNames(JsonNode operation) {
        Set<String> names = new TreeSet<>();
        for (JsonNode parameter : operation.path("parameters")) {
            names.add(parameter.path("in").asString() + ":" + parameter.path("name").asString());
        }
        return names;
    }
}
