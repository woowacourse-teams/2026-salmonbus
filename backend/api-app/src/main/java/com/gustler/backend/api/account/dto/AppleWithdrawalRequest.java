package com.gustler.backend.api.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 탈퇴를 그만두거나 실패했을 때 돌아갈 경로. 탈퇴를 마치면 이 값과 상관없이 {@code /?withdrawn=1} 로 간다. 비어 있거나 허용하지 않는
 * 경로면 첫 화면으로 돌아간다.
 */
public record AppleWithdrawalRequest(
    @Schema(description = "탈퇴를 그만두거나 실패했을 때 돌아갈 사이트 안 경로. / 또는 /routes/{9자리 노선 ID}이며 "
        + "노선 경로에는 direction(UP|DOWN)·stopId 쿼리만 한 번씩 붙일 수 있다. 생략하거나 허용하지 않는 값이면 /",
        example = "/routes/123456789?direction=UP")
    String returnTo
) {
}
