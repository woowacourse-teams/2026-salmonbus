package com.gustler.backend.quota.domain;

import com.gustler.backend.quota.api.CallQuota;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

/**
 * 서비스, 한국 날짜, 키로 구분하는 호출 한도 및 예약 규칙.
 * 신규 장부의 한도는 설정값을 사용하고, 기존 장부의 잔여 한도는 저장소가 원자적으로 판정한다.
 */
public record DailyCallQuota(CallQuota service, LocalDate koreanDate, String keyAlias, int dailyLimit) {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    public DailyCallQuota {
        Objects.requireNonNull(service, "호출 서비스가 필요하다");
        Objects.requireNonNull(koreanDate, "한국 날짜가 필요하다");
        if (keyAlias == null || keyAlias.isBlank()) {
            throw new IllegalArgumentException("키 별칭이 필요하다");
        }
        if (dailyLimit <= 0) {
            throw new IllegalArgumentException("하루 호출 한도는 1 이상이어야 한다");
        }
    }

    /** 요청 시각을 한국 날짜로 바꾸어 하루 한도 정책을 만든다. 실제 예약은 저장소가 수행한다. */
    public static DailyCallQuota at(
        CallQuota service,
        String keyAlias,
        OffsetDateTime requestedAt,
        final int dailyLimit
    ) {
        return new DailyCallQuota(service, koreanDateOf(requestedAt), keyAlias, dailyLimit);
    }

    /** 요청 전체가 설정 한도 안에 있을 때만 저장소에 전달할 예약을 만든다. */
    public Optional<Reservation> reservationFor(final int calls) {
        requirePositiveCalls(calls);
        if (calls > dailyLimit) {
            return Optional.empty();
        }
        return Optional.of(new Reservation(this, calls));
    }

    /** 전송 시각의 한국 날짜가 이 장부의 날짜와 같은지 확인한다. */
    public boolean covers(OffsetDateTime dispatchAt) {
        return koreanDate.equals(koreanDateOf(dispatchAt));
    }

    private static LocalDate koreanDateOf(OffsetDateTime requestedAt) {
        return Objects.requireNonNull(requestedAt, "호출 시각이 필요하다")
            .atZoneSameInstant(KOREA).toLocalDate();
    }

    private static void requirePositiveCalls(final int calls) {
        if (calls <= 0) {
            throw new IllegalArgumentException("예약할 호출 횟수는 1 이상이어야 한다");
        }
    }

    /** 아직 확정되지 않은 예약 요청. 저장소의 원자 갱신이 성공한 뒤에만 호출할 수 있다. */
    public record Reservation(DailyCallQuota quota, int calls) {
        public Reservation {
            Objects.requireNonNull(quota, "일일 호출 한도가 필요하다");
            requirePositiveCalls(calls);
            if (calls > quota.dailyLimit()) {
                throw new IllegalArgumentException("예약할 호출 횟수가 하루 한도를 초과한다");
            }
        }
    }
}
