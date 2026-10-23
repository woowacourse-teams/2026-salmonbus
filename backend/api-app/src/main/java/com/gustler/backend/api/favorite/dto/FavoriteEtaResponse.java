package com.gustler.backend.api.favorite.dto;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.gustler.backend.api.favorite.application.FavoriteOverview;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@JsonInclude(Include.NON_NULL)
@Schema(requiredProperties = "status")
public record FavoriteEtaResponse(
    FavoriteOverview.EtaStatus status,
    FavoriteOverview.EtaReason reason,
    Instant expectedArrivalAt,
    Long remainingSeconds
) {
    static FavoriteEtaResponse from(FavoriteOverview.Eta eta) {
        return new FavoriteEtaResponse(eta.status(), eta.reason(), eta.expectedArrivalAt(), eta.remainingSeconds());
    }
}
