package com.gustler.backend.api.board.dto;

import com.gustler.backend.api.board.domain.ForecastModel;
import java.time.OffsetDateTime;

public record ModelInfoResponse(
    String releaseId,
    OffsetDateTime trainedThrough
) {

    static ModelInfoResponse from(final ForecastModel model) {
        return new ModelInfoResponse(model.releaseId(), model.trainedThrough());
    }
}
