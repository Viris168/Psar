package com.psar.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces the modular monolith rules from essential-architecture.md:
 *  - a module never uses another module's repositories or entities
 *  - controllers never use repositories directly
 *  - pure pricing logic does not depend on Spring or repositories
 *  - ShedLock-locked jobs return void (ShedLock refuses to lock other methods at runtime)
 */
class ModuleBoundaryTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.psar");
    }

    @ParameterizedTest(name = "only {0} may use {0}.repository")
    @ValueSource(strings = {
            "user", "delivery", "catalog", "inventory", "order", "payment",
            "promotion", "loyalty", "subscription", "notification", "audit", "ai"
    })
    void repositoriesArePrivateToTheirModule(String module) {
        noClasses()
                .that().resideOutsideOfPackage("com.psar." + module + "..")
                .should().dependOnClassesThat().resideInAPackage("com.psar." + module + ".repository..")
                .because("modules talk through services or events, never another module's repository")
                .check(classes);
    }

    @ParameterizedTest(name = "only {0} may use {0}.entity")
    @ValueSource(strings = {
            "user", "delivery", "catalog", "inventory", "order", "payment",
            "promotion", "loyalty", "subscription", "notification", "audit", "ai"
    })
    void entitiesArePrivateToTheirModule(String module) {
        noClasses()
                .that().resideOutsideOfPackage("com.psar." + module + "..")
                .should().dependOnClassesThat().resideInAPackage("com.psar." + module + ".entity..")
                .because("other modules use DTOs or IDs, not entities")
                .check(classes);
    }

    @Test
    void controllersDoNotUseRepositories() {
        noClasses()
                .that().resideInAPackage("..controller..")
                .should().dependOnClassesThat().resideInAPackage("..repository..")
                .because("controllers stay thin and call services")
                .check(classes);
    }

    @Test
    void pricingIsPureLogic() {
        noClasses()
                .that().resideInAPackage("com.psar.order.service.pricing..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta.persistence..", "..repository..")
                .because("pricing must be pure, fast and easy to unit test")
                .check(classes);
    }

    @Test
    void onlyStockServiceChangesProductQuantity() {
        noClasses()
                .that().doNotHaveFullyQualifiedName("com.psar.inventory.service.StockService")
                .and().doNotHaveFullyQualifiedName("com.psar.catalog.service.ProductStockService")
                .should().dependOnClassesThat().haveFullyQualifiedName("com.psar.catalog.service.ProductStockService")
                .because("every stock change must write a stock_movements row, which StockService does")
                .check(classes);
    }

    @Test
    void lockedJobsReturnVoid() {
        methods()
                .that().areAnnotatedWith(SchedulerLock.class)
                .should().haveRawReturnType(void.class)
                .because("ShedLock throws LockingNotSupportedException on every run of a method returning a value")
                .check(classes);
    }
}
