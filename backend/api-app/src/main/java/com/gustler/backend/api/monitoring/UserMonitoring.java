package com.gustler.backend.api.monitoring;

import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.api.MemberRegistrationEvents;
import com.gustler.backend.member.api.MemberStatistics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 회원과 로그인 상태를 개인정보 없는 제한된 값으로 계측한다. */
@Component
public class UserMonitoring implements MemberRegistrationEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger(UserMonitoring.class);

    private final MeterRegistry meters;
    private final MemberStatistics members;
    private final Clock clock;
    private final Snapshot snapshot = new Snapshot();

    public UserMonitoring(MeterRegistry meters, MemberStatistics members, Clock clock) {
        this.meters = meters;
        this.members = members;
        this.clock = clock;
        Gauge.builder("salmonbus.users.current", snapshot, value -> value.currentMembers)
            .description("Current registered members")
            .register(meters);
        Gauge.builder("salmonbus.users.snapshot.timestamp", snapshot, value -> value.updatedAt)
            .description("Unix timestamp of the last successful current-member snapshot")
            .register(meters);
        registerCounters();
    }

    @Override
    public void succeeded(LoginProvider provider) {
        registrationCounter(provider, "success").increment();
    }

    @Override
    public void failed(LoginProvider provider, Failure failure) {
        String result = failure.name().toLowerCase(Locale.ROOT);
        registrationCounter(provider, result).increment();
        LOGGER.warn("event=user_registration status=FAILED provider={} reason={}", tag(provider), result);
    }

    public void login(LoginProvider provider, LoginResult result, MemberKind member) {
        login(Optional.of(provider), result, member);
    }

    public void login(Optional<LoginProvider> provider, LoginResult result, MemberKind member) {
        loginCounter(provider.map(UserMonitoring::tag).orElse("unknown"), result, member).increment();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void started() {
        refreshCurrentMembers();
    }

    @Scheduled(fixedDelayString = "${monitoring.users.refresh-interval:5m}", initialDelayString = "5m")
    public void refreshCurrentMembers() {
        try {
            snapshot.currentMembers = members.currentMembers();
            snapshot.updatedAt = clock.instant().getEpochSecond();
        } catch (RuntimeException exception) {
            LOGGER.warn("event=user_count_refresh status=FAILED cause={}", exception.getClass().getSimpleName());
        }
    }

    private static String tag(LoginProvider provider) {
        return provider.name().toLowerCase(Locale.ROOT);
    }

    private void registerCounters() {
        for (LoginProvider provider : LoginProvider.values()) {
            registrationCounter(provider, "success");
            for (Failure failure : Failure.values()) {
                registrationCounter(provider, failure.name().toLowerCase(Locale.ROOT));
            }
            registerLoginCounters(tag(provider));
        }
        registerLoginCounters("unknown");
    }

    private void registerLoginCounters(String provider) {
        for (LoginResult result : LoginResult.values()) {
            for (MemberKind member : MemberKind.values()) {
                loginCounter(provider, result, member);
            }
        }
    }

    private Counter registrationCounter(LoginProvider provider, String result) {
        return meters.counter("salmonbus.user.registration",
            "provider", tag(provider),
            "result", result);
    }

    private Counter loginCounter(String provider, LoginResult result, MemberKind member) {
        return meters.counter("salmonbus.user.login",
            "provider", provider,
            "result", result.name().toLowerCase(Locale.ROOT),
            "member", member.name().toLowerCase(Locale.ROOT));
    }

    public enum LoginResult {
        SUCCESS,
        CANCELLED,
        FAILED
    }

    public enum MemberKind {
        NEW,
        EXISTING,
        UNKNOWN
    }

    private static final class Snapshot {
        private volatile double currentMembers = Double.NaN;
        private volatile double updatedAt = Double.NaN;
    }
}
