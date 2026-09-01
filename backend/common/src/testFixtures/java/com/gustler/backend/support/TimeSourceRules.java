package com.gustler.backend.support;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.Date;

public final class TimeSourceRules {

    @ArchTest
    public static final ArchRule timeMustComeFromInjectedClock = noClasses()
        .should().callMethod(Instant.class, "now")
        .orShould().callMethod(LocalDate.class, "now")
        .orShould().callMethod(LocalDateTime.class, "now")
        .orShould().callMethod(ZonedDateTime.class, "now")
        .orShould().callMethod(System.class, "currentTimeMillis")
        .orShould().callConstructor(Date.class)
        .because("서버 타임존에 따라 값이 달라진다. 시각은 주입받은 Clock 에서만 얻는다");
}
