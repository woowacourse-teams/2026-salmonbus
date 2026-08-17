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
    static final ArchRule apiShouldNotDependOnCollectorOrProcessor = noClasses()
        .that().resideInAPackage("com.gustler.backend.api..")
        .should().dependOnClassesThat()
        .resideInAnyPackage(
            "com.gustler.backend.collector..",
            "com.gustler.backend.processor.."
        )
        .because("api는 collector나 processor를 모른다");

    @ArchTest
    static final ArchRule collectorShouldNotDependOnApiOrProcessor = noClasses()
        .that().resideInAPackage("com.gustler.backend.collector..")
        .should().dependOnClassesThat()
        .resideInAnyPackage(
            "com.gustler.backend.api..",
            "com.gustler.backend.processor.."
        )
        .because("collector는 api나 processor를 모른다");

    @ArchTest
    static final ArchRule processorShouldNotDependOnApiOrCollector = noClasses()
        .that().resideInAPackage("com.gustler.backend.processor..")
        .should().dependOnClassesThat()
        .resideInAnyPackage(
            "com.gustler.backend.api..",
            "com.gustler.backend.collector.."
        )
        .because("processor는 api나 collector를 모른다");
}
