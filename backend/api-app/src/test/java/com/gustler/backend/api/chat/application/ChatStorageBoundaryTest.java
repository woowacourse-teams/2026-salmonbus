package com.gustler.backend.api.chat.application;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.gustler.backend.api.chat", importOptions = ImportOption.DoNotIncludeTests.class)
class ChatStorageBoundaryTest {

    @ArchTest
    static final ArchRule applicationAndDomainDoNotUseMongoTypes = noClasses()
        .that().resideInAnyPackage("com.gustler.backend.api.chat.application..", "com.gustler.backend.api.chat.domain..")
        .should().dependOnClassesThat().resideInAnyPackage("com.mongodb..", "org.bson..");
}
