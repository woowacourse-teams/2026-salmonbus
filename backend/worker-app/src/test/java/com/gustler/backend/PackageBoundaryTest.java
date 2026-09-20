package com.gustler.backend;

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
    static final ArchRule collectorShouldNotDependOnProcessor = noClasses()
        .that().resideInAPackage("com.gustler.backend.collector..")
        .should().dependOnClassesThat()
        .resideInAnyPackage("com.gustler.backend.processor..", "com.gustler.backend.forecast..")
        .because("collector는 processor를 모른다");

    @ArchTest
    static final ArchRule processorShouldNotDependOnCollector = noClasses()
        .that().resideInAnyPackage("com.gustler.backend.processor..", "com.gustler.backend.forecast..")
        .should().dependOnClassesThat()
        .resideInAPackage("com.gustler.backend.collector..")
        .because("processor는 collector를 모른다");
}
