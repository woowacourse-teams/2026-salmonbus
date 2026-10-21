package com.gustler.backend.member.application;

import com.gustler.backend.member.api.MemberProfile;
import com.gustler.backend.member.api.ViewMember;
import com.gustler.backend.member.domain.MemberRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class MemberViewer implements ViewMember {
    private final MemberRepository memberRepository;

    public MemberViewer(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    @Override
    public Optional<MemberProfile> view(UUID publicId) {
        return memberRepository.findProfile(publicId);
    }
}
