package com.j4mb.payment_orchestrator.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures;

/**
 * Guards the architecture the packages express, so the boundaries hold as the code grows.
 *
 * <p>These rules are the reason a single Maven module is enough: they fail the build for exactly the
 * violations separate modules would have made impossible to compile.
 */
@AnalyzeClasses(
        packages = ArchitectureTest.ROOT,
        importOptions = {ImportOption.DoNotIncludeTests.class})
class ArchitectureTest {

    static final String ROOT = "com.j4mb.payment_orchestrator";

    private static final String DOMAIN = ROOT + ".payments.domain..";
    private static final String APPLICATION = ROOT + ".payments.application..";
    private static final String INFRASTRUCTURE = ROOT + ".payments.infrastructure..";

    @ArchTest
    static final ArchRule domain_is_framework_free = noClasses()
            .that()
            .resideInAPackage(DOMAIN)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..",
                    "jakarta.persistence..",
                    "jakarta.validation..",
                    "jakarta.servlet..",
                    "com.fasterxml.jackson..",
                    "io.swagger..")
            .because("the domain model must stay independent of any framework");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_outer_layers = noClasses()
            .that()
            .resideInAPackage(DOMAIN)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(APPLICATION, INFRASTRUCTURE)
            .because("dependencies point inward: the domain is the innermost layer");

    @ArchTest
    static final ArchRule application_does_not_depend_on_infrastructure = noClasses()
            .that()
            .resideInAPackage(APPLICATION)
            .should()
            .dependOnClassesThat()
            .resideInAPackage(INFRASTRUCTURE)
            .because("use cases talk to ports, never to the adapters that implement them");

    @ArchTest
    static final ArchRule application_is_persistence_free = noClasses()
            .that()
            .resideInAPackage(APPLICATION)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("jakarta.persistence..", "org.springframework.data..")
            .because("persistence is an adapter concern behind PaymentRepositoryPort");

    @ArchTest
    static final ArchRule layers_are_respected = Architectures.layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Domain")
            .definedBy(DOMAIN)
            .layer("Application")
            .definedBy(APPLICATION)
            .layer("Infrastructure")
            .definedBy(INFRASTRUCTURE)
            .whereLayer("Infrastructure")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("Application")
            .mayOnlyBeAccessedByLayers("Infrastructure")
            .because("this is the Clean Architecture dependency rule for the payments context");

    @ArchTest
    static final ArchRule outbound_ports_are_interfaces = classes()
            .that()
            .resideInAPackage(ROOT + ".payments.application.port..")
            .and()
            .haveSimpleNameNotEndingWith("Command")
            .and()
            .areTopLevelClasses()
            .should()
            .beInterfaces()
            .because("a port is a contract, not an implementation");

    @ArchTest
    static final ArchRule adapters_live_in_the_infrastructure_layer = classes()
            .that()
            .haveSimpleNameEndingWith("Adapter")
            .should()
            .resideInAPackage(INFRASTRUCTURE)
            .because("adapters are infrastructure by definition");

    @ArchTest
    static final ArchRule controllers_live_in_the_web_adapter = classes()
            .that()
            .haveSimpleNameEndingWith("Controller")
            .should()
            .resideInAPackage(ROOT + "..adapter.in.web..")
            .because("HTTP is one inbound adapter among several, not a layer of its own");
}
