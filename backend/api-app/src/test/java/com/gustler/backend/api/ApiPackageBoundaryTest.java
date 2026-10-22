package com.gustler.backend.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.error.ApiException;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import java.util.stream.Stream;

/**
 * 조회 API의 계층 경계를 검사한다.
 *
 * <p>업무 모듈의 경계는 worker-app의 PackageBoundaryTest가 검사한다. 이 검사는 worker의
 * 클래스패스만 읽으므로 api-app의 승객 조회 경로는 여기서 별도로 검사한다.
 *
 * <p>조회 경로의 쓰기 업무 모듈 의존성은 두 검사로 나눠 확인한다. 여기서는 참조하는 쪽의
 * 바이트코드를 검사하고, build.gradle의 {@code verifyRuntimeProjects}는 런타임 클래스패스의
 * 간접 프로젝트 의존성까지 검사한다. {@code compileOnly}처럼 런타임 클래스패스에 없는
 * 의존성은 이 규칙이 검사하고, 우리 패키지 이름을 거치지 않는 경로는 Gradle 검사가 담당한다.
 *
 * <p>회원 모듈({@code member})은 로그인 경로가 쓰는 쓰기 모듈이라 위 금지 목록에 두지 않는다. 대신 공개 계약
 * 패키지 {@code member.api}로만 쓰게 아래 규칙으로 막는다.
 *
 * <p>오류 처리는 두 패키지로 나뉜다. {@code api.error}는 외부 계약인 코드·메시지·본문 형식을,
 * {@code api.http}는 예외 처리기·오류 컨트롤러·필터·Valve 같은 요청 처리 장치를 담는다.
 * 조회 계층은 계약만 참조한다.
 */
@AnalyzeClasses(packages = "com.gustler.backend", importOptions = DoNotIncludeTests.class)
class ApiPackageBoundaryTest {
    private static final String PREFIX = "com.gustler.backend.api.";
    private static final String TRANSPORT = "com.gustler.backend.api.http..";
    private static final String CONTRACT = "com.gustler.backend.api.error..";
    private static final String ARRIVAL_HTTP_CLIENT = PREFIX + "arrival.infrastructure.http..";
    private static final List<String> FEATURES = List.of("arrival", "board", "chat", "route", "vehicle");
    private static final List<String> BUSINESS_MODULES = List.of(
        "com.gustler.backend.routecatalog..", "com.gustler.backend.observations..",
        "com.gustler.backend.forecasting..", "com.gustler.backend.arrivals..",
        "com.gustler.backend.quota..", "com.gustler.backend.gbis..");
    private static final List<String> READ_LAYERS = List.of("..domain..", "..application..", "..infrastructure..");
    private static final List<String> REQUEST_HANDLING = List.of(
        "org.springframework.web..", "jakarta.servlet..", "org.apache.catalina..");

    @ArchTest
    static final ArchRule READ_PATH_DOES_NOT_USE_BUSINESS_WRITE_MODULES = noClasses()
        .that().resideInAPackage("com.gustler.backend..")
        .should().dependOnClassesThat().resideInAnyPackage(BUSINESS_MODULES.toArray(String[]::new));

    @ArchTest
    static final ArchRule MEMBER_MODULE_IS_USED_ONLY_THROUGH_ITS_API = noClasses()
        .that().resideInAPackage("com.gustler.backend.api..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "com.gustler.backend.member.domain..", "com.gustler.backend.member.application..",
            "com.gustler.backend.member.infrastructure..", "com.gustler.backend.member.configuration..");

    @ArchTest
    static final ArchRule DOMAIN_USES_ONLY_PLAIN_TYPES = noClasses()
        .that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework..", "jakarta.persistence..", "java.sql..", "javax.sql..",
            "tools.jackson..", "com.fasterxml.jackson..");

    @ArchTest
    static final ArchRule ARRIVAL_CONTRACT_USES_ONLY_PLAIN_TYPES = noClasses()
        .that().resideInAPackage("com.gustler.backend.arrival.contract..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework..", "jakarta..", "java.sql..", "javax.sql..",
            "tools.jackson..", "com.fasterxml.jackson..", "..application..", "..infrastructure..",
            "..controller..", "..dto..", "..configuration..");

    @ArchTest
    static final ArchRule DOMAIN_DOES_NOT_DEPEND_ON_OUTER_LAYERS = noClasses()
        .that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..application..", "..infrastructure..", "..controller..", "..dto..");

    @ArchTest
    static final ArchRule APPLICATION_USES_PERSISTENCE_PORTS = noClasses()
        .that().resideInAPackage("..application..")
        .should().dependOnClassesThat().resideInAPackage("..infrastructure..");

    @ArchTest
    static final ArchRule VEHICLE_DOES_NOT_DEPEND_ON_BOARD = noClasses()
        .that().resideInAPackage(PREFIX + "vehicle..")
        .should().dependOnClassesThat().resideInAPackage(PREFIX + "board..");

    @ArchTest
    static final ArchRule READ_LAYERS_DO_NOT_DEPEND_ON_WEB_ADAPTERS = noClasses()
        .that().resideInAnyPackage(READ_LAYERS.toArray(String[]::new))
        .should().dependOnClassesThat().resideInAnyPackage("..controller..", "..dto..");

    /**
     * 요청 처리 장치는 자기 패키지 밖에서 쓰이지 않는다.
     *
     * <p>기술 타입의 직접 의존성만 검사하면 감싼 클래스를 통한 간접 의존성을 놓칠 수 있다.
     * ArchUnit의 {@code dependOnClassesThat}은 직접 의존만 보므로, 조회 계층이 {@code RequestId}를
     * 참조하면 그 뒤의 서블릿 의존성은 검사하지 못한다. 이 규칙은 요청 처리 패키지의 사용처를
     * 검사해 그 경로를 차단한다. 참조되는 쪽에서 사용처를 찾는 규칙이므로 분석 범위는
     * {@code com.gustler.backend} 전체이며 실행 앱 클래스도 읽는다.
     */
    @ArchTest
    static final ArchRule TRANSPORT_ADAPTER_IS_USED_ONLY_INSIDE_ITSELF = classes()
        .that().resideInAPackage(TRANSPORT)
        .should().onlyHaveDependentClassesThat().resideInAPackage(TRANSPORT);

    /**
     * 조회 계층은 요청·응답 기술을 직접 다루지 않는다.
     *
     * <p>{@code org.springframework.http}까지 함께 막는다. 컨트롤러는 {@code CacheControl}과
     * {@code ResponseEntity}를 쓰지만 그 아래 세 계층은 쓰지 않는다. 상태코드를 아는 코드가
     * 경계 밖으로 내려오지 않게 한다.
     *
     * <p>Worker로 갱신을 요청하는 HTTP 클라이언트는 별도 경계다. 클라이언트 기술 의존만
     * 허용하고, 아래 규칙으로 서버 요청 처리 기술은 계속 금지한다.
     */
    @ArchTest
    static final ArchRule READ_LAYERS_DO_NOT_TOUCH_WEB_TECHNOLOGY = noClasses()
        .that().resideInAnyPackage(READ_LAYERS.toArray(String[]::new))
        .and().resideOutsideOfPackage(ARRIVAL_HTTP_CLIENT)
        .should().dependOnClassesThat().resideInAnyPackage(
            concat(REQUEST_HANDLING, "org.springframework.http.."));

    @ArchTest
    static final ArchRule ARRIVAL_HTTP_CLIENT_DOES_NOT_HANDLE_SERVER_REQUESTS = noClasses()
        .that().resideInAPackage(ARRIVAL_HTTP_CLIENT)
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.web.bind..", "org.springframework.web.servlet..",
            "org.springframework.web.context..", "org.springframework.web.filter..",
            "org.springframework.web.server..", "org.springframework.web.socket..",
            "org.springframework.web.method..", "org.springframework.http.server..",
            "jakarta.servlet..", "org.apache.catalina..");

    /** 계약 패키지는 계약만 담는다. 요청을 다루는 장치가 여기로 섞여 들어오면 분리가 무너진다. */
    @ArchTest
    static final ArchRule ERROR_CONTRACT_HOLDS_NO_REQUEST_HANDLING = noClasses()
        .that().resideInAPackage(CONTRACT)
        .should().dependOnClassesThat().resideInAnyPackage(REQUEST_HANDLING.toArray(String[]::new));

    /**
     * 조회 API가 외부에 반환하는 실패는 모두 같은 오류 계약을 따른다.
     *
     * <p>{@code ApiException}을 상속하지 않은 오류는 {@code handleUnexpected}에서
     * 500 INTERNAL_ERROR로 처리되어 의도한 상태코드와 오류코드가 응답에 실리지 않는다.
     * 이 규칙은 오류 클래스의 상속 누락을 검사한다.
     */
    @ArchTest
    static final ArchRule READ_PATH_ERRORS_SHARE_ONE_VOCABULARY = classes()
        .that().haveSimpleNameEndingWith("Exception").and().resideInAPackage(PREFIX + ".")
        .should().beAssignableTo(ApiException.class);

    /** 기능 루트는 그 기능의 경계 어휘만 둔다. 계층 클래스나 프레임워크 타입이 오면 잘못 놓인 것이다. */
    @ArchTest
    static final ArchRule FEATURE_ROOTS_HOLD_ONLY_BOUNDARY_VOCABULARY = noClasses()
        .that().resideInAnyPackage(FEATURES.stream()
                .map(PREFIX::concat)
                .toArray(String[]::new))
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

    /**
     * 오류 계약과 요청 처리 장치가 실제로 분리되어 있는지 검사한다. 어느 한쪽이 비어 규칙이 무의미해지는 것을 막는다.
     */
    @ArchTest
    static void 오류_계약과_요청_처리_장치가_갈려_있다(JavaClasses classes) {
        assertThat(classes.stream()
            .filter(type -> type.getPackageName().equals(PREFIX + "error"))
            .count())
            .as("api.error에 오류 계약 클래스가 있어야 한다")
            .isGreaterThanOrEqualTo(4);
        assertThat(classes.stream()
            .filter(type -> type.getPackageName().equals(PREFIX + "http"))
            .count())
            .as("api.http에 요청 처리 장치가 있어야 한다")
            .isGreaterThanOrEqualTo(4);
    }

    private static String[] concat(List<String> packages, String extra) {
        return Stream.concat(packages.stream(), Stream.of(extra))
            .toArray(String[]::new);
    }
}
