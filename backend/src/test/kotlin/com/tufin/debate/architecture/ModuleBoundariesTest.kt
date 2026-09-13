package com.tufin.debate.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/** Enforces the modular-monolith boundaries from docs/architecture.md §3. */
class ModuleBoundariesTest {

    private val importedClasses = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("com.tufin.debate")

    private val modules = listOf("identity", "discussion", "participants", "permissions", "audit")

    @Test
    fun `domain layers do not depend on application, api, or infrastructure layers`() {
        noClasses().that().resideInAPackage("com.tufin.debate..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "com.tufin.debate..application..",
                "com.tufin.debate..api..",
                "com.tufin.debate..infrastructure..",
            )
            .check(importedClasses)
    }

    @Test
    fun `controllers live only in api packages`() {
        classes().that().areAnnotatedWith(org.springframework.web.bind.annotation.RestController::class.java)
            .should().resideInAPackage("com.tufin.debate..api..")
            .check(importedClasses)
    }

    @Test
    fun `no module reaches into another module's infrastructure`() {
        modules.forEach { module ->
            noClasses().that().resideOutsideOfPackage("com.tufin.debate.$module..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.tufin.debate.$module.infrastructure..")
                .because("cross-module access goes through application services, not repositories")
                .check(importedClasses)
        }
    }
}
