package com.gustler.backend.api.board.dto;

import com.gustler.backend.api.board.domain.ForecastModel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(requiredProperties = {"releaseId", "trainedThrough"})
public record ModelInfoResponse(
    @Schema(description = "예보 모델 배포 식별자")
    String releaseId,
    @Schema(description = "모델이 학습에 사용한 데이터의 마지막 시각. ISO-8601 오프셋 포함")
    OffsetDateTime trainedThrough
) {

    static ModelInfoResponse from(
        ForecastModel model
    ) {
        return new ModelInfoResponse(model.releaseId(), model.trainedThrough());
    }
}
