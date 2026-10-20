package com.gustler.backend.api.chat.application;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.gustler.backend.api.chat", importOptions = DoNotIncludeTests.class)
class ChatStorageBoundaryTest {
    @ArchTest
    static final ArchRule APPLICATION_AND_DOMAIN_DO_NOT_USE_MONGO_TYPES = noClasses()
        .that()
        .resideInAnyPackage("com.gustler.backend.api.chat.application..", "com.gustler.backend.api.chat.domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("com.mongodb..", "org.bson..");
}
