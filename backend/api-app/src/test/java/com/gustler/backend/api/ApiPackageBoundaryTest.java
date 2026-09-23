package com.gustler.backend.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;

/**
 * 조회 API의 계층 경계를 검사한다.
 *
 * <p>업무 모듈의 경계는 worker-app의 PackageBoundaryTest가 본다. 그 검사는 worker의 클래스패스를
 * 읽으므로 api-app 클래스를 한 줄도 보지 않는다. 승객 조회 경로는 여기서 따로 검사한다.
 */
@AnalyzeClasses(packages = "com.gustler.backend.api", importOptions = ImportOption.DoNotIncludeTests.class)
class ApiPackageBoundaryTest {

    private static final String PREFIX = "com.gustler.backend.api.";
    private static final List<String> FEATURES = List.of("board", "route", "vehicle");
    private static final List<String> BUSINESS_MODULES = List.of(
        "com.gustler.backend.routecatalog..", "com.gustler.backend.observations..",
        "com.gustler.backend.forecasting..", "com.gustler.backend.quota..", "com.gustler.backend.gbis..");

    @ArchTest
    static final ArchRule domainUsesOnlyPlainTypes = noClasses()
        .that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework..", "jakarta.persistence..", "java.sql..", "javax.sql..",
            "tools.jackson..", "com.fasterxml.jackson..");

    @ArchTest
    static final ArchRule domainDoesNotDependOnOuterLayers = noClasses()
        .that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..application..", "..infrastructure..", "..controller..", "..dto..");

    @ArchTest
    static final ArchRule applicationUsesPersistencePorts = noClasses()
        .that().resideInAPackage("..application..")
        .should().dependOnClassesThat().resideInAPackage("..infrastructure..");

    @ArchTest
    static final ArchRule applicationDoesNotDependOnHttp = noClasses()
        .that().resideInAPackage("..application..")
        .should().dependOnClassesThat().resideInAnyPackage("..controller..", "..dto..");

    @ArchTest
    static final ArchRule readPathDoesNotUseBusinessWriteModules = noClasses()
        .that().resideInAPackage("com.gustler.backend.api..")
        .should().dependOnClassesThat().resideInAnyPackage(BUSINESS_MODULES.toArray(String[]::new));

    /** 클래스패스가 비어 규칙이 조용히 통과하는 일을 막는다. */
    @ArchTest
    static void 조회_기능의_계층이_검사_대상에_있다(JavaClasses classes) {
        for (String feature : FEATURES) {
            for (String layer : List.of("domain", "application", "infrastructure", "controller")) {
                assertThat(classes.stream()
                    .anyMatch(type -> type.getPackageName().startsWith(PREFIX + feature + "." + layer)))
                    .as("%s의 %s 계층 클래스가 검사 대상에 있어야 한다", feature, layer)
                    .isTrue();
            }
        }
    }
}
