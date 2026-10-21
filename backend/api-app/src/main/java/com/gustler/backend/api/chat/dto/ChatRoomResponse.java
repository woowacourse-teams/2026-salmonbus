package com.gustler.backend.api.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 공개 조회 응답에 현재 노선 채팅방의 이용 가능 정보와 표시값을 담는다.
 */
@Schema(requiredProperties = {"routeId", "displayName", "historySize", "maxBodyCodePoints"})
public record ChatRoomResponse(
    @Schema(description = "노선 채팅방의 9자리 노선 ID")
    String routeId,
    @Schema(description = "노선 표시 이름")
    String displayName,
    @Schema(description = "연결 직후 전송하는 최근 메시지의 최대 개수")
    int historySize,
    @Schema(description = "보낼 수 있는 메시지 본문의 최대 Unicode 코드포인트 수")
    int maxBodyCodePoints
) {
}
