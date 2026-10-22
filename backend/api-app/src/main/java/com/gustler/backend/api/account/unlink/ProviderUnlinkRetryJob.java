package com.gustler.backend.api.account.unlink;

import com.gustler.backend.member.api.PendingUnlink;
import com.gustler.backend.member.api.ProviderUnlinks;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 실패한 제공자 연결 해제를 주기적으로 다시 시도하고, 포기한 요청을 보관 기간(30일) 뒤 지운다.
 *
 * <p>한 번의 실행에서 잡는 요청 수와 묶음 수에 상한을 둔다. 요청은 잡는 순간 이 서버 몫이 되어 다른 서버가 동시에 시도하지
 * 않는다. 다시 시도할 시각과 포기할 횟수는 회원 모듈이 정한다.
 */
public final class ProviderUnlinkRetryJob {
    /**
     * 한 번에 잡는 요청 수. 요청마다 제공자 호출이 최대 8초(연결 3초, 응답 5초) 걸리므로, 한 묶음을 다 시도하는 시간이 임대
     * 시간(5분)보다 짧아야 다른 서버가 같은 요청을 다시 잡지 않는다. 20 × 8초 = 160초다.
     */
    static final int BATCH_SIZE = 20;
    static final int MAX_BATCHES_PER_RUN = 20;

    private final ProviderUnlinks unlinks;
    private final ProviderUnlinkRunner runner;

    public ProviderUnlinkRetryJob(ProviderUnlinks unlinks, ProviderUnlinkRunner runner) {
        this.unlinks = unlinks;
        this.runner = runner;
    }

    /** 포기한 요청을 보관 기간이 지나면 지운다. 하루 한 번이면 충분하다. */
    @Scheduled(cron = "${auth.unlink.purge-cron:0 23 4 * * *}")
    public void purgeGaveUp() {
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            if (unlinks.purgeGaveUp() < ProviderUnlinks.PURGE_BATCH_SIZE) {
                return;
            }
        }
    }

    @Scheduled(cron = "${auth.unlink.retry-cron:30 */5 * * * *}")
    public void retry() {
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            List<PendingUnlink> due = unlinks.claimDue(BATCH_SIZE);
            due.forEach(runner::attempt);
            if (due.size() < BATCH_SIZE) {
                return;
            }
        }
    }
}
