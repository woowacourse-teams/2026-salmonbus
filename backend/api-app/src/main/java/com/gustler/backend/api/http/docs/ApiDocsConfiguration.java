package com.gustler.backend.api.http.docs;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class ApiDocsConfiguration {

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
