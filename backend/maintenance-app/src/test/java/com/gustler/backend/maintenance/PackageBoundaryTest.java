package com.gustler.backend.maintenance;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

@AnalyzeClasses(packages = "com.gustler.backend.maintenance", importOptions = ImportOption.DoNotIncludeTests.class)
class PackageBoundaryTest {

    private static final String PREFIX = "com.gustler.backend.";
    private static final String APP = PREFIX + "maintenance";

    @ArchTest
    static final ArchRule commandsUsePublicContracts = classes().should(
        new ArchCondition<>("업무 모듈의 공개 API를 사용하고 실행 구성에서만 공개 구성을 가져온다") {
            @Override
            public void check(JavaClass source, ConditionEvents events) {
                for (Dependency dependency : source.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    String packageName = target.getPackageName();
                    if (!packageName.startsWith(PREFIX) || inPackage(target, APP)) {
                        continue;
                    }
                    String relative = packageName.substring(PREFIX.length());
                    int separator = relative.indexOf('.');
                    String module = separator < 0 ? relative : relative.substring(0, separator);
                    boolean api = inPackage(target, PREFIX + module + ".api");
                    boolean configuration = inPackage(source, APP + ".configuration")
                        && inPackage(target, PREFIX + module + ".configuration");
                    events.add(new SimpleConditionEvent(dependency, api || configuration, dependency.getDescription()));
                }
            }
        });

    @ArchTest
    static void applicationClassesAreActuallyInspected(JavaClasses classes) {
        assertThat(classes.stream().anyMatch(type -> type.getName().equals(MaintenanceApplication.class.getName())))
            .as("정비 앱의 실제 진입점이 검사 대상에 있어야 한다").isTrue();
        assertThat(classes.stream().anyMatch(type -> inPackage(type, APP + ".configuration")))
            .as("정비 앱의 실제 구성이 검사 대상에 있어야 한다").isTrue();
    }

    private static boolean inPackage(JavaClass type, String name) {
        return type.getPackageName().equals(name) || type.getPackageName().startsWith(name + ".");
    }
}
