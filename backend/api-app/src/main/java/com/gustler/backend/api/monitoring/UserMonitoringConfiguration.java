package com.gustler.backend.api.monitoring;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 회원 표본 갱신이 인증이나 채팅 기능의 스케줄 설정에 기대지 않게 한다. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class UserMonitoringConfiguration {
}
