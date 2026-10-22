package com.gustler.backend.api.account.token;

import com.gustler.backend.member.api.RefreshTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 쓸 데가 없어진 리프레시 토큰을 주기적으로 지운다.
 *
 * <p>30일 넘게 갱신하지 않았거나 처음 로그인한 뒤 180일이 지난 계통은 다시 제시될 때 지워지므로, 돌아오지 않으면
 * 행이 영구히 남는다. 한 번에 지우는 개수에 상한이 있어, 상한만큼 지웠으면 이어서 부르되 한 번의 실행에서 부르는 횟수도
 * 제한한다. 서버가 여러 대여도 같은 행을 두 번 지우지 않도록 저장소가 잠긴 행을 건너뛴다. {@code auth.enabled} 가
 * {@code true} 일 때만 등록한다.
 */
@Component
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
public class RefreshTokenPurge {
    static final String DEFAULT_CRON = "0 17 * * * *";
    static final int MAX_BATCHES_PER_RUN = 100;

    private static final Logger LOGGER = LoggerFactory.getLogger(RefreshTokenPurge.class);

    private final RefreshTokens refreshTokens;

    public RefreshTokenPurge(RefreshTokens refreshTokens) {
        this.refreshTokens = refreshTokens;
    }

    @Scheduled(cron = "${auth.token.purge-cron:" + DEFAULT_CRON + "}")
    public void purge() {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            final int deleted = refreshTokens.purgeObsolete();
            total += deleted;
            if (deleted < RefreshTokens.PURGE_BATCH_SIZE) {
                break;
            }
        }
        if (total > 0) {
            LOGGER.info("쓸 데가 없는 리프레시 토큰을 지웠다. count={}", total);
        }
    }
}
