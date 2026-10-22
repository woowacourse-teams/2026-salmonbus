package com.gustler.backend.member.api;

import java.util.UUID;

public interface ChangeNickname {
    /**
     * 앞뒤 공백 제거와 NFC 정규화는 이 안에서 한다. 자기 닉네임과 같은 값으로 바꾸거나 대소문자만 바꾸는 것은 성공한다.
     * 같은 닉네임으로 동시에 바꾸려는 요청이 겹치면 한쪽만 성공하고 다른 쪽은 {@link NicknameChangeResult.Taken} 이다.
     */
    NicknameChangeResult change(UUID publicId, String nickname);
}
