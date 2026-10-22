package com.gustler.backend.member.application;

import com.gustler.backend.member.api.ChangeNickname;
import com.gustler.backend.member.api.NicknameChangeResult;
import com.gustler.backend.member.api.NicknameViolation;
import com.gustler.backend.member.domain.MemberRepository;
import com.gustler.backend.member.domain.Nickname;
import com.gustler.backend.member.domain.NicknameUpdate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class NicknameChanger implements ChangeNickname {
    private final MemberRepository memberRepository;

    public NicknameChanger(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    @Override
    public NicknameChangeResult change(UUID publicId, String nickname) {
        Optional<NicknameViolation> violation = Nickname.violationOf(nickname);
        if (violation.isPresent()) {
            return new NicknameChangeResult.Invalid(violation.get());
        }
        return switch (memberRepository.changeNickname(publicId, new Nickname(nickname))) {
            case UPDATED -> memberRepository.findProfile(publicId)
                .<NicknameChangeResult>map(NicknameChangeResult.Changed::new)
                .orElseGet(NicknameChangeResult.MemberNotFound::new);
            case TAKEN -> new NicknameChangeResult.Taken();
            case MEMBER_NOT_FOUND -> new NicknameChangeResult.MemberNotFound();
        };
    }
}
