package com.gustler.backend.api.http.token;

import com.gustler.backend.api.account.token.AccessTokens;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TokenAuthenticationConfiguration {
    @Bean
    TokenAuthentication tokenAuthentication(ObjectProvider<AccessTokens> accessTokens) {
        return new TokenAuthentication(accessTokens);
    }
}
