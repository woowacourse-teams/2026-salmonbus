package com.gustler.backend.api.http.docs;

import com.gustler.backend.api.docs.ApiDocsTags;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 공개 API 문서와 nullable 응답 스키마를 실제 전송 계약에 맞춰 구성한다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class ApiDocsConfiguration {
    /** 화면이 쓰는 흐름 순서. 목록에 없는 경로는 뒤에 생성 순서대로 둔다. */
    private static final List<String> PATH_ORDER = List.of(
        "/api/v1/routes",
        "/api/v1/routes/{routeId}/watch",
        "/api/v1/routes/{routeId}/board",
        "/api/v1/routes/{routeId}/vehicles",
        "/api/v2/routes/{routeId}/geometry",
        "/api/v2/stations/{stationId}/arrivals",
        "/api/auth/session",
        "/api/auth/refresh",
        "/api/auth/logout",
        "/api/me",
        "/api/me/favorite-groups",
        "/api/me/favorite-groups/{groupId}",
        "/api/me/favorite-groups/order",
        "/api/me/favorite-groups/{groupId}/favorites/{favoriteId}",
        "/api/me/favorite-groups/{groupId}/favorites/order",
        "/api/me/favorite-selections",
        "/api/me/favorite-order",
        "/api/me/favorites",
        "/api/me/sessions",
        "/api/me/withdrawal/apple"
    );

    @Bean
    OpenApiCustomizer nullableSchemas() {
        return document -> {
            Components components = document.getComponents();
            if (components == null || components.getSchemas() == null) {
                return;
            }
            Map<String, Schema> schemas = components.getSchemas();
            for (Schema<?> model : schemas.values()) {
                if (model.getProperties() == null) {
                    continue;
                }
                for (Schema<?> property : model.getProperties().values()) {
                    if (property.getTypes() == null || !property.getTypes().contains("null")) {
                        continue;
                    }
                    allowNullEnum(property);
                    if (property.get$ref() == null) {
                        continue;
                    }
                    // 3.1에서 $ref와 type:null을 함께 쓰면 객체도 null도 만족할 수 없다.
                    // DTO의 nullable 의미를 참조 객체 또는 null로 표현하고 원본 컴포넌트는 바꾸지 않는다.
                    String reference = property.get$ref();
                    property.set$ref(null);
                    property.setType(null);
                    property.setTypes(null);
                    property.setNullable(null);
                    Schema<Object> referenceSchema = new Schema<>();
                    referenceSchema.set$ref(reference);
                    Schema<Object> nullSchema = new Schema<>();
                    nullSchema.setSpecVersion(SpecVersion.V31);
                    nullSchema.setTypes(Set.of("null"));
                    property.addAnyOfItem(referenceSchema);
                    property.addAnyOfItem(nullSchema);
                }
            }
        };
    }

    private static <T> void allowNullEnum(Schema<T> property) {
        if (property.getEnum() == null) {
            return;
        }
        ArrayList<T> values = new ArrayList<>(property.getEnum());
        if (!values.contains(null)) {
            values.add(null);
            property.setEnum(values);
        }
    }

    /**
     * 실제로 문서에 실린 operation 의 묶음만 정해 둔 순서로 두고, 경로도 화면 흐름 순서로 둔다.
     * 꺼진 기능의 묶음은 빈 제목으로 남지 않는다.
     */
    @Bean
    OpenApiCustomizer orderedTagsAndPaths() {
        return document -> {
            Set<String> used = new HashSet<>();
            if (document.getPaths() != null) {
                document.getPaths().values().forEach(path -> path.readOperations().stream()
                    .filter(operation -> operation.getTags() != null)
                    .forEach(operation -> used.addAll(operation.getTags())));
            }
            List<Tag> tags = new ArrayList<>();
            for (Tag tag : ApiDocsTags.ordered()) {
                if (used.contains(tag.getName())) {
                    tags.add(tag);
                }
            }
            document.setTags(tags);
            if (document.getPaths() == null) {
                return;
            }
            Paths ordered = new Paths();
            document.getPaths().entrySet().stream()
                .sorted(Comparator.comparingInt(path -> orderOf(path.getKey())))
                .forEach(path -> ordered.addPathItem(path.getKey(), path.getValue()));
            document.setPaths(ordered);
        };
    }

    private static int orderOf(String path) {
        final int index = PATH_ORDER.indexOf(path);
        if (index < 0) {
            return PATH_ORDER.size();
        }
        return index;
    }

    @Bean
    OpenAPI salmonbusOpenApi(BuildProperties buildProperties) {
        return new OpenAPI()
            .info(new Info()
                .title("Salmonbus API")
                .description("""
                    현재 구현된 HTTP API 계약입니다. 예시는 합성 데이터입니다.

                    - 로그인이 꺼져 있으면 로그인·회원 경로가 명세에서 빠집니다.
                    - 오류 응답은 `Cache-Control: no-store`이고 본문은 `code`·`message`·`requestId`입니다. 분기는 `code`로 합니다.
                    - dev 백엔드 연결 확인 전까지 Try it out은 꺼져 있습니다.
                    """)
                .version(buildProperties.getVersion() + " / built " + buildProperties.getTime()))
            .addServersItem(new Server().url("/"));
    }
}
