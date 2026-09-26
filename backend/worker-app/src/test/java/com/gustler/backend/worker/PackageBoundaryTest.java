package com.gustler.backend.worker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaMethodReference;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.gustler.backend.config.ClockConfig;
import com.gustler.backend.gbis.api.GbisApiCaller;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;

@AnalyzeClasses(packages = "com.gustler.backend", importOptions = ImportOption.DoNotIncludeTests.class)
class PackageBoundaryTest {

    private static final String PREFIX = "com.gustler.backend.";
    private static final List<String> MODULES = List.of("routecatalog", "observations", "forecasting", "quota", "gbis");

    @ArchTest
    static final ArchRule domainUsesOnlyBusinessTypes = noClasses()
        .that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework..", "jakarta.persistence..", "java.sql..", "javax.sql..",
            "java.net.http..", "org.apache.hc..", "okhttp3..",
            "tools.jackson..", "com.fasterxml.jackson..", "..infrastructure..", "..application..", "..configuration..");

    @ArchTest
    static final ArchRule applicationUsesPersistencePorts = noClasses()
        .that().resideInAPackage("..application..")
        .should().dependOnClassesThat().resideInAPackage("..infrastructure..");

    @ArchTest
    static final ArchRule businessLogicDoesNotUseProviderTypes = noClasses()
        .that().resideInAnyPackage("..application..", "..domain..")
        .should().dependOnClassesThat().resideInAPackage(PREFIX + "gbis..");

    @ArchTest
    static final ArchRule persistenceDoesNotRunPublicUseCases = classes()
        .that().resideInAnyPackage("..infrastructure.jpa..", "..infrastructure.jdbc..")
        .should(new ArchCondition<>("저장 어댑터에서 업무 모듈의 공개 기능을 실행하지 않는다") {
            @Override
            public void check(JavaClass source, ConditionEvents events) {
                for (JavaAccess<?> access : source.getAccessesFromSelf()) {
                    if (!(access instanceof JavaMethodCall) && !(access instanceof JavaMethodReference)) {
                        continue;
                    }
                    JavaClass target = access.getTarget().getOwner();
                    String targetModule = moduleOf(target);
                    if (targetModule != null && target.isInterface()
                        && inPackage(target, PREFIX + targetModule + ".api")) {
                        events.add(SimpleConditionEvent.violated(access, access.getDescription()));
                    }
                }
            }
        });

    @ArchTest
    static final ArchRule librariesDoNotDependOnExecutableApps = noClasses()
        .that().resideInAnyPackage(MODULES.stream().map(name -> PREFIX + name + "..").toArray(String[]::new))
        .should().dependOnClassesThat().resideInAnyPackage(
            PREFIX + "worker..", PREFIX + "maintenance..", PREFIX + "api..");

    @ArchTest
    static final ArchRule businessModulesHaveNoCycles = slices()
        .matching("com.gustler.backend.(*)..")
        .should().beFreeOfCycles();

    @ArchTest
    static final ArchRule domainConceptsHaveNoCycles = slices()
        .matching("com.gustler.backend.(*).domain.(*)..")
        .should().beFreeOfCycles();

    @ArchTest
    static final ArchRule otherModulesUsePublicContracts = classes()
        .that().resideInAnyPackage(MODULES.stream().map(name -> PREFIX + name + "..").toArray(String[]::new))
        .should(new ArchCondition<>("다른 업무 모듈의 공개 계약만 사용한다") {
            @Override
            public void check(JavaClass source, ConditionEvents events) {
                String sourceModule = moduleOf(source);
                for (Dependency dependency : source.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    String targetModule = moduleOf(target);
                    if (targetModule == null || targetModule.equals(sourceModule)) {
                        continue;
                    }
                    boolean publicContract = inPackage(target, PREFIX + targetModule + ".api");
                    events.add(new SimpleConditionEvent(dependency, publicContract, dependency.getDescription()));
                }
            }
        });

    @ArchTest
    static final ArchRule workerRunsOnlyPublicUseCases = noClasses()
        .that().resideInAnyPackage(PREFIX + "worker.scheduling..", PREFIX + "worker.startup..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..domain..", "..application..", "..infrastructure..");

    @ArchTest
    static void businessPackagesAreActuallyInspected(JavaClasses classes) {
        for (String module : MODULES) {
            assertThat(classes.stream().anyMatch(type -> module.equals(moduleOf(type))))
                .as("%s 모듈의 실제 클래스가 검사 대상에 있어야 한다", module)
                .isTrue();
        }
        for (String module : List.of("routecatalog", "observations", "forecasting", "quota")) {
            assertThat(classes.stream().anyMatch(type -> type.getPackageName().startsWith(PREFIX + module + ".domain")))
                .as("%s의 도메인 클래스가 검사 대상에 있어야 한다", module)
                .isTrue();
        }
    }

    @ArchTest
    static void sharedLibrariesHaveNoBusinessOrExecutionDependencies(JavaClasses classes) {
        verifyLibrary(ClockConfig.class, "common", List.of(
            PREFIX + "routecatalog..", PREFIX + "observations..", PREFIX + "forecasting..", PREFIX + "quota..",
            PREFIX + "gbis..", PREFIX + "worker..", PREFIX + "maintenance..", PREFIX + "api.."));
        verifyLibrary(GbisApiCaller.class, "gbis-client", List.of(
            PREFIX + "routecatalog..", PREFIX + "observations..", PREFIX + "forecasting..", PREFIX + "quota..",
            PREFIX + "worker..", PREFIX + "maintenance..", PREFIX + "api.."));
    }

    private static void verifyLibrary(Class<?> entry, String name, List<String> forbiddenPackages) {
        JavaClasses library = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
            .importUrl(entry.getProtectionDomain().getCodeSource().getLocation());
        assertThat(library.stream().anyMatch(type -> type.getName().equals(entry.getName())))
            .as("%s의 실제 실행 클래스가 검사 대상에 있어야 한다", name).isTrue();
        noClasses().should().dependOnClassesThat()
            .resideInAnyPackage(forbiddenPackages.toArray(String[]::new)).check(library);
    }

    private static boolean inPackage(JavaClass type, String name) {
        return type.getPackageName().equals(name) || type.getPackageName().startsWith(name + ".");
    }

    private static String moduleOf(JavaClass type) {
        return MODULES.stream().filter(module -> inPackage(type, PREFIX + module))
            .findFirst().orElse(null);
    }
}
