package com.gustler.backend.forecasting.domain.evaluation;

/** 저장 당시 예측값과 공개 노선·정류장 표시명. 차량·회원 식별자는 포함하지 않는다. */
public record EvaluationDiagnostics(String routeName, String stopName, String stopId, String direction,
    long modelDeploymentId, Double expectedSeats, double fullChance) { }
