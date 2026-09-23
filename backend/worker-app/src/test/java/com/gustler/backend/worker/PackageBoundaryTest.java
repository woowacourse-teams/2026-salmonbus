package com.gustler.backend.worker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
    packages = "com.gustler.backend",
    importOptions = ImportOption.DoNotIncludeTests.class
)
public class PackageBoundaryTest {

    @ArchTest
    static final ArchRule collectionShouldNotDependOnForecasting = noClasses()
        .that().resideInAPackage("com.gustler.backend.observations.application..")
        .should().dependOnClassesThat()
        .resideInAPackage("com.gustler.backend.forecasting..")
        .because("관측 수집은 예보 구현에 의존하지 않는다");

    @ArchTest
    static final ArchRule forecastingShouldNotDependOnCollectionInternals = noClasses()
        .that().resideInAPackage("com.gustler.backend.forecasting..")
        .should().dependOnClassesThat()
        .resideInAPackage("com.gustler.backend.observations.application..")
        .because("예보는 수집의 응용 서비스를 참조하지 않는다");
}
