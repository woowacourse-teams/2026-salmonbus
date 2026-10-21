package com.gustler.backend.routecatalog.domain;

/**
 * 노선 기본정보 수신의 성공과 실패를 구분한다.
 * 통신 실패를 빈 정상 노선으로 바꾸지 않는다.
 */
public sealed interface RouteSourceResult {
    record Success(UpstreamRoute route) implements RouteSourceResult {
    }

    record Failed(String reason) implements RouteSourceResult {
    }
}
