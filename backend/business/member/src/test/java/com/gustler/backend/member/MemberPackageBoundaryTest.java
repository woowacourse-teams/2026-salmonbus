package com.gustler.backend.member;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;

/**
 * 회원 모듈의 계층 경계를 검사한다. 이 모듈은 api-app 의 로그인 경로가 {@code member.api} 로만 쓴다.
 */
@AnalyzeClasses(packages = "com.gustler.backend.member", importOptions = DoNotIncludeTests.class)
class MemberPackageBoundaryTest {
    private static final String PREFIX = "com.gustler.backend.member.";

    @ArchTest
    static final ArchRule DOMAIN_USES_ONLY_PLAIN_TYPES = noClasses()
        .that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework..", "jakarta..", "java.sql..", "javax.sql..",
            "tools.jackson..", "com.fasterxml.jackson..", "..application..", "..infrastructure..",
            "..configuration..");

    @ArchTest
    static final ArchRule APPLICATION_USES_PERSISTENCE_PORTS = noClasses()
        .that().resideInAPackage("..application..")
        .should().dependOnClassesThat().resideInAPackage("..infrastructure..");

    @ArchTest
    static final ArchRule PUBLIC_API_HOLDS_ONLY_CONTRACTS = noClasses()
        .that().resideInAPackage(PREFIX + "api..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..domain..", "..application..", "..infrastructure..", "..configuration..", "org.springframework..");

    @ArchTest
    static final ArchRule MEMBER_DOES_NOT_DEPEND_ON_EXECUTABLE_APPS = noClasses()
        .that().resideInAPackage("com.gustler.backend.member..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "com.gustler.backend.api..", "com.gustler.backend.worker..", "com.gustler.backend.maintenance..");

    /** 클래스패스가 비어 규칙이 조용히 통과하는 일을 막는다. */
    @ArchTest
    static void 모든_계층이_검사_대상에_있다(JavaClasses classes) {
        List<String> layers = List.of("api", "domain", "application", "infrastructure.jdbc", "configuration");
        for (String layer : layers) {
            assertThat(classes.stream().anyMatch(type -> type.getPackageName().startsWith(PREFIX + layer)))
                .as("%s 계층 클래스가 검사 대상에 있어야 한다", layer)
                .isTrue();
        }
    }
}
