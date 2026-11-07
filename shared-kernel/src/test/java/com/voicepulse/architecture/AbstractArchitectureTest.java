package com.voicepulse.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.AnnotationVisitor;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.ClassWriter;
import com.tngtech.archunit.thirdparty.org.objectweb.asm.Opcodes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

public abstract class AbstractArchitectureTest {

    protected static JavaClasses classes;

    @BeforeAll
    static void setup() {
        classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("com.voicepulse");
    }

    protected static ArchRule domainMustBePureRule() {
        return noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "io.netty..",
                        "org.apache.kafka..",
                        "com.fasterxml.jackson..",
                        "jakarta.persistence..",
                        "javax.persistence..")
                .allowEmptyShould(true);
    }

    @Nested
    @DisplayName("Hexagonal Boundary Enforcement")
    class HexagonalBoundaries {

        @Test
        @DisplayName(
                "Domain must NOT depend on Spring, Netty, Kafka, Jackson, or Jakarta Persistence")
        void domainMustBePure() {
            domainMustBePureRule().check(classes);
        }

        @Test
        @DisplayName("Inbound and Outbound Ports must be Java interfaces")
        void portsMustBeInterfaces() {
            classes()
                    .that()
                    .resideInAPackage("..domain.ports..")
                    .and()
                    .haveSimpleNameNotEndingWith("package-info")
                    .should()
                    .beInterfaces()
                    .allowEmptyShould(true)
                    .check(classes);
        }
    }

    @Nested
    @DisplayName("Naming Conventions")
    class NamingConventions {

        @Test
        @DisplayName("Inbound ports must end with 'UseCase'")
        void inboundPortNaming() {
            classes()
                    .that()
                    .resideInAPackage("..domain.ports.inbound..")
                    .and()
                    .haveSimpleNameNotEndingWith("package-info")
                    .should()
                    .haveSimpleNameEndingWith("UseCase")
                    .allowEmptyShould(true)
                    .check(classes);
        }

        @Test
        @DisplayName("Outbound ports must end with 'Port'")
        void outboundPortNaming() {
            classes()
                    .that()
                    .resideInAPackage("..domain.ports.outbound..")
                    .and()
                    .haveSimpleNameNotEndingWith("package-info")
                    .should()
                    .haveSimpleNameEndingWith("Port")
                    .allowEmptyShould(true)
                    .check(classes);
        }

        @Test
        @DisplayName("Kafka listeners must end with 'Listener' or 'Consumer'")
        void kafkaListenerNaming() {
            classes()
                    .that()
                    .resideInAPackage("..infrastructure.kafka.consumer..")
                    .and()
                    .haveSimpleNameNotEndingWith("package-info")
                    .should()
                    .haveSimpleNameEndingWith("Listener")
                    .orShould()
                    .haveSimpleNameEndingWith("Consumer")
                    .allowEmptyShould(true)
                    .check(classes);
        }
    }

    @Nested
    @DisplayName("Quality and Safety Guardrails")
    class QualityAndSafety {

        @Test
        @DisplayName("Field injection is strictly prohibited (@Autowired on fields)")
        void noFieldInjection() {
            noFields()
                    .should()
                    .beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
                    .allowEmptyShould(true)
                    .check(classes);
        }

        @Test
        @DisplayName("Direct console output (System.out / System.err) is prohibited")
        void noStandardStreams() {
            GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS
                    .allowEmptyShould(true)
                    .check(classes);
        }

        @Test
        @DisplayName("Production Java source files must not exceed 600 lines")
        void fileLineLimit() throws IOException {
            Path srcMain = Path.of("src", "main", "java");
            if (Files.exists(srcMain)) {
                try (Stream<Path> stream = Files.walk(srcMain)) {
                    stream.filter(Files::isRegularFile)
                            .filter(path -> path.toString().endsWith(".java"))
                            .forEach(
                                    path -> {
                                        try {
                                            long lineCount = Files.lines(path).count();
                                            assertTrue(
                                                    lineCount <= 600,
                                                    "File "
                                                            + path
                                                            + " exceeds 600 lines: "
                                                            + lineCount);
                                        } catch (IOException e) {
                                            throw new RuntimeException(e);
                                        }
                                    });
                }
            }
        }

        @Test
        @DisplayName(
                "ArchUnit rule must fail when an illegal framework dependency is imported in"
                        + " domain")
        void archUnitFailsOnIllegalDependency() throws IOException {
            Path tempDir = Files.createTempDirectory("archtest-illegal-domain");
            try {
                Path pkgDir = tempDir.resolve("com/voicepulse/synthetic/domain");
                Files.createDirectories(pkgDir);

                ClassWriter cw = new ClassWriter(0);
                cw.visit(
                        Opcodes.V21,
                        Opcodes.ACC_PUBLIC,
                        "com/voicepulse/synthetic/domain/IllegalDomainService",
                        null,
                        "java/lang/Object",
                        null);
                AnnotationVisitor av =
                        cw.visitAnnotation("Lorg/springframework/stereotype/Component;", true);
                if (av != null) {
                    av.visitEnd();
                }
                cw.visitEnd();

                Files.write(pkgDir.resolve("IllegalDomainService.class"), cw.toByteArray());

                JavaClasses syntheticClasses = new ClassFileImporter().importPath(tempDir);
                AssertionError error =
                        assertThrows(
                                AssertionError.class,
                                () -> domainMustBePureRule().check(syntheticClasses));
                assertTrue(
                        error.getMessage().contains("IllegalDomainService")
                                || error.getMessage().contains("Component"),
                        "Expected failure message mentioning the violation, but got: "
                                + error.getMessage());
            } finally {
                try (Stream<Path> walk = Files.walk(tempDir)) {
                    walk.sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(java.io.File::delete);
                }
            }
        }
    }
}
