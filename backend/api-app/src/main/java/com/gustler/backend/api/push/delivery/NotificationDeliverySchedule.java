package com.gustler.backend.api.push.delivery;

import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

/**
 * 발송 작업만 도는 스레드 하나다. 기본 예약 스레드를 다른 작업과 같이 쓰면, 그 작업이 외부 호출로 멈추는 동안
 * 알림이 재시도 기한을 넘겨 GAVE_UP이 된다. 이 스케줄러를 TaskScheduler 빈으로 등록하면 Spring이 모든
 * {@code @Scheduled} 작업의 기본 스케줄러로 골라 버려서, 빈이 아닌 필드로 둔다.
 */
public final class NotificationDeliverySchedule implements AutoCloseable {
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

    NotificationDeliverySchedule(NotificationDeliveryJob job, String cron) {
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("push-delivery-");
        scheduler.initialize();
        scheduler.schedule(job::run, new CronTrigger(cron));
    }

    @Override
    public void close() {
        scheduler.shutdown();
    }
}
