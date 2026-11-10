package com.gustler.backend.forecasting.application;

/** 노선 작업을 직렬화하고 기존 아카이브의 입력 판본을 읽는다. */
public interface RouteWorkAccess {
    long current(long routeVersionId);

    long lock(long routeVersionId);

    long lockByRoute(long routeId);
}
