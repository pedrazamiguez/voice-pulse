package com.voicepulse.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Architectural Invariants Verification - Shared Kernel")
public class ArchitectureTest extends AbstractArchitectureTest {

    @Test
    @DisplayName(
            "Shared Kernel domain models and events must remain 100% pure (zero Kafka, Spring,"
                    + " Jackson, Lombok)")
    void sharedKernelDomainPurity() {
        noClasses()
                .that()
                .resideInAnyPackage("..shared.model..", "..shared.event..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "io.netty..",
                        "org.apache.kafka..",
                        "com.fasterxml.jackson..",
                        "jakarta.persistence..",
                        "javax.persistence..",
                        "lombok..")
                .allowEmptyShould(true)
                .check(classes);
    }
}
