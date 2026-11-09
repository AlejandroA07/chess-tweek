package com.chesstweek;

import org.springframework.web.bind.annotation.RestController;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.chesstweek", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule shared_does_not_depend_on_features_or_composition = noClasses()
            .that().resideInAPackage("..shared..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..identity..", "..gamereview..", "..training..", "..composition..");

    @ArchTest
    static final ArchRule identity_does_not_reach_other_modules = noClasses()
            .that().resideInAPackage("..identity..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..gamereview..", "..training..", "..composition..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule game_review_does_not_reach_other_modules = noClasses()
            .that().resideInAPackage("..gamereview..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..identity..", "..training..", "..composition..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule training_does_not_reach_other_modules = noClasses()
            .that().resideInAPackage("..training..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..identity..", "..gamereview..", "..composition..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule composition_uses_only_public_feature_packages = noClasses()
            .that().resideInAPackage("..composition..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..identity.internal..", "..gamereview.internal..", "..training.internal..");

    @ArchTest
    static final ArchRule database_access_stays_in_owned_persistence_packages = noClasses()
            .that().resideOutsideOfPackages(
                    "..shared.database..",
                    "..identity.internal.persistence..",
                    "..gamereview.internal.persistence..",
                    "..training.internal.persistence..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "java.sql..",
                    "javax.sql..",
                    "org.flywaydb..",
                    "org.sqlite..",
                    "org.springframework.jdbc..",
                    "com.zaxxer.hikari..");

    @ArchTest
    static final ArchRule web_controllers_stay_in_composition = classes()
            .that().areAnnotatedWith(RestController.class)
            .should().resideInAPackage("..composition.web..");
}
