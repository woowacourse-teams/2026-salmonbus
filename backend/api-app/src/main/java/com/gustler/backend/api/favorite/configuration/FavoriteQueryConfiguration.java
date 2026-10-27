package com.gustler.backend.api.favorite.configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class FavoriteQueryConfiguration {
    @Bean(defaultCandidate = false, destroyMethod = "close")
    public ExecutorService favoriteArrivalExecutor() {
        // 추가 스레드는 4개까지 사용한다. 제출할 자리가 없으면 서비스가 요청 스레드에서 처리한다.
        return new ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS, new SynchronousQueue<>(),
            Thread.ofPlatform().name("favorite-arrival-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }
}
