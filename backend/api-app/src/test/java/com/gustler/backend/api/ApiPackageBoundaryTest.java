package com.gustler.backend.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.error.ApiException;
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
 *
 * <p>조회 경로가 쓰기 업무 모듈을 끌어오지 않는다는 것은 두 곳에서 본다. 여기서는 참조하는 쪽
 * 바이트코드를 읽어 막고, build.gradle의 {@code verifyRuntimeProjects}가 런타임 클래스패스의 프로젝트
 * 의존을 간접 경로까지 막는다. 둘 다 필요하다. 이 규칙은 {@code compileOnly}로 들어온 의존처럼
 * 런타임 클래스패스에 안 잡히는 경로를 보고, gradle 검사는 우리 패키지 이름을 거치지 않고 들어오는
 * 경로를 본다.
 *
 * <p>오류는 두 패키지로 갈려 있다. {@code api.error}는 밖으로 나가는 계약(코드·메시지·본문 모양)이고
 * {@code api.http}는 그 계약을 요청·응답에 싣는 장치(예외 처리기·오류 컨트롤러·필터·Valve)다.
 * 조회 계층은 계약만 알고 장치는 모른다.
 */
@AnalyzeClasses(packages = "com.gustler.backend", importOptions = ImportOption.DoNotIncludeTests.class)
class ApiPackageBoundaryTest {

    private static final String PREFIX = "com.gustler.backend.api.";
    private static final String TRANSPORT = "com.gustler.backend.api.http..";
    private static final String CONTRACT = "com.gustler.backend.api.error..";
    private static final List<String> FEATURES = List.of("board", "route", "vehicle");
    private static final List<String> BUSINESS_MODULES = List.of(
        "com.gustler.backend.routecatalog..", "com.gustler.backend.observations..",
        "com.gustler.backend.forecasting..", "com.gustler.backend.quota..", "com.gustler.backend.gbis..");
    private static final List<String> READ_LAYERS = List.of("..domain..", "..application..", "..infrastructure..");
    private static final List<String> REQUEST_HANDLING = List.of(
        "org.springframework.web..", "jakarta.servlet..", "org.apache.catalina..");

    @ArchTest
    static final ArchRule readPathDoesNotUseBusinessWriteModules = noClasses()
        .that().resideInAPackage("com.gustler.backend..")
        .should().dependOnClassesThat().resideInAnyPackage(BUSINESS_MODULES.toArray(String[]::new));

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
    static final ArchRule readLayersDoNotDependOnWebAdapters = noClasses()
        .that().resideInAnyPackage(READ_LAYERS.toArray(String[]::new))
        .should().dependOnClassesThat().resideInAnyPackage("..controller..", "..dto..");

    /**
     * 요청 처리 장치는 자기 패키지 밖에서 쓰이지 않는다.
     *
     * <p>이것이 계층 경계를 실제로 지키는 규칙이다. 기술 이름으로 막는 아래 규칙은 우리가 감싼
     * 클래스를 거쳐 들어오는 길을 못 본다 — ArchUnit의 {@code dependOnClassesThat}은 직접 의존만
     * 보므로, 조회 계층이 {@code RequestId}를 부르면 그 뒤의 서블릿 의존은 한 홉 밖이라 걸리지 않는다.
     * 패키지로 막으면 그 길이 닫힌다. 역방향을 보는 규칙이라 분석 범위가 {@code com.gustler.backend}
     * 전체여야 하고, 그래서 이 검사는 실행 앱 클래스까지 읽는다.
     */
    @ArchTest
    static final ArchRule transportAdapterIsUsedOnlyInsideItself = classes()
        .that().resideInAPackage(TRANSPORT)
        .should().onlyHaveDependentClassesThat().resideInAPackage(TRANSPORT);

    /**
     * 조회 계층은 요청·응답 기술을 직접 다루지 않는다.
     *
     * <p>{@code org.springframework.http}까지 함께 막는다. 컨트롤러는 {@code CacheControl}과
     * {@code ResponseEntity}를 쓰지만 그 아래 세 계층은 쓰지 않으며, 상태코드를 아는 자리가
     * 경계 밖으로 내려오지 않게 한다.
     */
    @ArchTest
    static final ArchRule readLayersDoNotTouchWebTechnology = noClasses()
        .that().resideInAnyPackage(READ_LAYERS.toArray(String[]::new))
        .should().dependOnClassesThat().resideInAnyPackage(
            concat(REQUEST_HANDLING, "org.springframework.http.."));

    /** 계약 패키지는 계약만 담는다. 요청을 다루는 장치가 여기로 섞여 들어오면 분리가 무너진다. */
    @ArchTest
    static final ArchRule errorContractHoldsNoRequestHandling = noClasses()
        .that().resideInAPackage(CONTRACT)
        .should().dependOnClassesThat().resideInAnyPackage(REQUEST_HANDLING.toArray(String[]::new));

    /**
     * 조회 API가 밖으로 내는 실패는 모두 한 어휘를 쓴다.
     *
     * <p>{@code ApiException}을 벗어나면 {@code handleUnexpected}가 받아 500 INTERNAL_ERROR로 뭉개진다.
     * 의도한 상태코드와 오류코드가 응답에 실리지 않는다. 상속을 빠뜨리는 것을 여기서 잡는다.
     */
    @ArchTest
    static final ArchRule readPathErrorsShareOneVocabulary = classes()
        .that().haveSimpleNameEndingWith("Exception").and().resideInAPackage(PREFIX + ".")
        .should().beAssignableTo(ApiException.class);

    /** 기능 루트는 그 기능의 경계 어휘만 둔다. 계층 클래스나 프레임워크 타입이 오면 자리를 잘못 잡은 것이다. */
    @ArchTest
    static final ArchRule featureRootsHoldOnlyBoundaryVocabulary = noClasses()
        .that().resideInAnyPackage(FEATURES.stream().map(PREFIX::concat).toArray(String[]::new))
        .should().dependOnClassesThat().resideInAnyPackage(
            "..application..", "..infrastructure..", "..controller..", "..dto..",
            "org.springframework..", "jakarta..", "tools.jackson..", "com.fasterxml.jackson..");

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

    /** 오류 계약과 요청 처리 장치가 실제로 갈려 있는지 본다. 한쪽이 비면 위 규칙들이 헛돈다. */
    @ArchTest
    static void 오류_계약과_요청_처리_장치가_갈려_있다(JavaClasses classes) {
        assertThat(classes.stream().filter(type -> type.getPackageName().equals(PREFIX + "error")).count())
            .as("api.error에 오류 계약 클래스가 있어야 한다")
            .isGreaterThanOrEqualTo(4);
        assertThat(classes.stream().filter(type -> type.getPackageName().equals(PREFIX + "http")).count())
            .as("api.http에 요청 처리 장치가 있어야 한다")
            .isGreaterThanOrEqualTo(4);
    }

    private static String[] concat(List<String> packages, String extra) {
        return java.util.stream.Stream.concat(packages.stream(), java.util.stream.Stream.of(extra))
            .toArray(String[]::new);
    }
}
