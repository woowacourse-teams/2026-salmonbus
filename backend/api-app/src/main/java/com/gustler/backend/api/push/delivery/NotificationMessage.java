package com.gustler.backend.api.push.delivery;

public record NotificationMessage(String title, String body) {
    static NotificationMessage unavailable() {
        return new NotificationMessage("연어버스", "버스 정보를 확인할 수 없어요. 앱에서 다시 확인해 주세요.");
    }
}
