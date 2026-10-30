package com.gustler.backend.api.http.login;

import com.gustler.backend.api.account.InvalidAppleNotificationException;
import com.gustler.backend.api.account.dto.AppleNotificationRequest;
import com.gustler.backend.api.error.ServiceUnavailableException;
import com.gustler.backend.api.http.login.AppleNotificationVerifier.Verification;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 애플 서버 간 알림을 받는다. 애플 서버가 직접 보내므로 쿠키도 CSRF 토큰도 없다.
 *
 * <p>검증한 알림은 회원이 있든 없든, 처리하는 종류든 아니든 200 이다. 애플이 같은 알림을 다시 보내지 않게 하려는 것이다. 검증하지 못한 알림은
 * 이유와 관계없이 400 {@code INVALID_REQUEST}, 애플 공개키를 받지 못하면 503 {@code SERVICE_UNAVAILABLE} 이다. 임시 세션은
 * 만들지 않는다. 로그인과 애플이 모두 켜졌을 때만 등록하므로, 아니면 다른 로그인 경로처럼 404 다.
 */
@RestController
@ConditionalOnProperty(prefix = "auth", name = {"enabled", "apple.enabled"}, havingValue = "true")
public class AppleNotificationController {
    private final AppleNotificationVerifier verifier;
    private final AppleNotifications notifications;

    AppleNotificationController(AppleNotificationVerifier verifier, AppleNotifications notifications) {
        this.verifier = verifier;
        this.notifications = notifications;
    }

    @PostMapping("/api/auth/apple/notifications")
    public ResponseEntity<Void> receive(@RequestBody(required = false) AppleNotificationRequest request) {
        String payload = request == null ? null : request.payload();
        switch (verifier.verify(payload)) {
            case Verification.Verified(AppleAccountEvent event) -> notifications.apply(event);
            case Verification.Rejected(Verification.Reason reason) -> {
                notifications.rejected(reason);
                throw new InvalidAppleNotificationException();
            }
            case Verification.Unavailable() -> {
                notifications.unavailable();
                throw new ServiceUnavailableException();
            }
        }
        return ResponseEntity.ok().build();
    }
}
