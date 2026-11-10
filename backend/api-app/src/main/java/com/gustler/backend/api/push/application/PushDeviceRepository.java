package com.gustler.backend.api.push.application;

import com.gustler.backend.api.push.domain.DeviceToken;
import com.gustler.backend.api.push.domain.PushEnvironment;
import java.time.Instant;
import java.util.UUID;

public interface PushDeviceRepository {
    /**
     * 기기 토큰을 이 회원의 기기로 저장한다. 다른 회원이 가진 토큰이면 그 행을 지우고 새 행으로 만들어,
     * 이전 회원의 행에 딸린 데이터가 새 회원에게 넘어가지 않게 한다.
     *
     * @return 회원과 로그인 세션이 있으면 true. 저장 여부가 아니라 둘의 존재 여부다
     */
    boolean register(UUID memberPublicId, UUID sessionId, DeviceToken token, PushEnvironment environment,
        Instant registeredAt);

    /**
     * 이 회원이 가진 토큰이면 지운다. 없거나 다른 회원의 토큰이면 아무것도 하지 않는다.
     *
     * @return 회원이 있으면 true. 지운 행이 있었는지가 아니라 회원 존재 여부다
     */
    boolean unregister(UUID memberPublicId, DeviceToken token);

    boolean deleteById(long deviceId);
}
