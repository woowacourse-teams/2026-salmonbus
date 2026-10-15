package com.gustler.backend.api.http.docs;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.media.Schema;
import java.util.ArrayList;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class ApiDocsConfiguration {

    @Bean
    OpenApiCustomizer nullableSchemas() {
        return document -> {
            if (document.getComponents() == null || document.getComponents().getSchemas() == null) return;
            for (Schema<?> model : document.getComponents().getSchemas().values()) {
                if (model.getProperties() == null) continue;
                for (Schema<?> property : model.getProperties().values()) {
                    if (property.getTypes() == null || !property.getTypes().contains("null")) continue;
                    allowNullEnum(property);
                    if (property.get$ref() == null) continue;
                    // 3.1에서 $ref와 type:null을 함께 쓰면 객체도 null도 만족할 수 없다.
                    // DTO의 nullable 의미를 참조 객체 또는 null로 표현하고 원본 컴포넌트는 바꾸지 않는다.
                    String reference = property.get$ref();
                    property.set$ref(null);
                    property.setType(null);
                    property.setTypes(null);
                    property.setNullable(null);
                    var referenceSchema = new Schema<>();
                    referenceSchema.set$ref(reference);
                    var nullSchema = new Schema<>();
                    nullSchema.setSpecVersion(SpecVersion.V31);
                    nullSchema.setTypes(Set.of("null"));
                    property.addAnyOfItem(referenceSchema);
                    property.addAnyOfItem(nullSchema);
                }
            }
        };
    }

    private static <T> void allowNullEnum(Schema<T> property) {
        if (property.getEnum() == null) return;
        var values = new ArrayList<>(property.getEnum());
        if (!values.contains(null)) {
            values.add(null);
            property.setEnum(values);
        }
    }

    @Bean
    OpenAPI salmonbusOpenApi(BuildProperties buildProperties) {
        return new OpenAPI()
            .info(new Info()
                .title("Salmonbus API")
                .description("현재 구현된 HTTP API 계약입니다. 예시는 합성 데이터이며, "
                    + "[WebSocket 메시지 계약](/api/docs/chat)은 별도 문서입니다. "
                    + "채팅 기능이 꺼져 있으면 HTTP 채팅 경로도 명세에서 빠집니다. "
                    + "dev 백엔드 연결 확인 전까지 Try it out은 꺼져 있습니다.")
                .version(buildProperties.getVersion() + " / built " + buildProperties.getTime()))
            .addServersItem(new Server().url("/"));
    }
}
