package com.gustler.backend.api.push.application;

import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.push.domain.DeliveryDecision;
import com.gustler.backend.api.push.domain.PushEnvironment;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NotificationDeliveryRepository {
    record ClaimedDelivery(
        long id,
        UUID settingId,
        long deviceId,
        String token,
        PushEnvironment environment,
        FavoriteTarget target,
        Instant occurrenceAt,
        int attempts
    ) {
        @Override
        public String toString() {
            return "ClaimedDelivery[id=" + id + "]";
        }
    }

    int createForMemberDevices(UUID settingId, Instant occurrenceAt, long memberId, Instant now);

    List<ClaimedDelivery> claimDue(Instant now, Instant leasedUntil, int limit);

    void record(long id, DeliveryDecision decision, Instant now);

    int deleteCompletedBefore(Instant cutoff, int limit);

    int giveUpOccurredBefore(Instant occurredBefore, Instant now);
}
