package com.gustler.backend.api.http;

import org.apache.catalina.core.StandardHost;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 톰캣의 기본 오류 화면을 서비스의 오류 응답 형식으로 바꾼다.
 *
 * <p>오류 valve 는 Engine 이 아니라 Host 에 등록된다. 따라서 valve 를 직접 넣는 대신
 * Host 에 사용할 클래스를 지정한다. 톰캣은 기동할 때 해당 클래스를 생성해 등록한다.
 * Engine 에 등록하면 Host 의 기본 valve 가 그대로 남아 먼저 응답을 작성한다.
 */
@Configuration
public class ContainerErrorResponseConfig {
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> containerErrorResponse() {
        return factory -> factory.addContextCustomizers(context -> {
            if (context.getParent() instanceof StandardHost host) {
                host.setErrorReportValveClass(ContainerErrorResponseValve.class.getName());
            }
        });
    }
}
