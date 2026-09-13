package com.gustler.backend.api.http;

import org.apache.catalina.core.StandardHost;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 톰캣의 기본 오류 화면을 우리 오류 응답 형식으로 바꾼다.
 *
 * <p>오류 valve 는 Engine 이 아니라 Host 에 붙는다. 그래서 valve 를 직접 넣는 대신
 * Host 에게 <b>어느 클래스를 쓸지</b>를 알려 준다. 톰캣이 기동할 때 그 클래스를 만들어 단다.
 * Engine 에 넣으면 Host 의 기본 valve 가 그대로 남아 먼저 응답을 써 버린다.
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
