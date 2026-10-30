package com.gustler.backend.api.account.analytics;

import com.gustler.backend.member.api.AnalyticsDeletions;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;

/** 탈퇴 호출자가 즉시 시도하고, 남은 요청은 주기 작업이 다시 처리한다. */
public final class AmplitudeDeletionProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(AmplitudeDeletionProcessor.class);
    private final AnalyticsDeletions deletions;
    private final ObjectProvider<AmplitudeDeletionClient> clients;

    public AmplitudeDeletionProcessor(AnalyticsDeletions deletions, ObjectProvider<AmplitudeDeletionClient> clients) {
        this.deletions = deletions;
        this.clients = clients;
    }

    /** 회원 삭제 트랜잭션이 끝난 뒤 호출한다. 실패해도 탈퇴 응답은 유지한다. */
    public void attempt(UUID publicId) {
        try {
            AmplitudeDeletionClient client = clients.getIfAvailable();
            if (client != null) {
                deletions.attempt(publicId, client::delete);
            }
        } catch (RuntimeException exception) {
            LOGGER.error("Amplitude 삭제 결과를 기록하지 못했다. 대기 기록으로 다시 시도한다");
        }
    }

    @Scheduled(cron = "${analytics.amplitude.retry-cron:15 */5 * * * *}")
    public void retry() {
        try {
            AmplitudeDeletionClient client = clients.getIfAvailable();
            if (client != null) {
                deletions.retry(client::delete);
            }
        } catch (RuntimeException exception) {
            LOGGER.error("Amplitude 삭제 재시도를 마치지 못했다. 다음 실행에서 다시 시도한다");
        }
    }
}
