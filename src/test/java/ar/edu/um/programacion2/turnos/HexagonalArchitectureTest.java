package ar.edu.um.programacion2.turnos;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Reglas de la arquitectura hexagonal, válidas para cualquier feature ({@code <base>.<feature>.domain|application|infrastructure}),
 * las mismas que en el catálogo (#12 y #35 del catálogo).
 * <p>
 * En los patrones, {@code *} es exactamente un segmento de paquete: {@code turnos.*.domain..} incluye
 * {@code turnos.reservation.domain.model}, pero no el {@code turnos.domain} de JHipster.
 * <p>
 * Todas las reglas usan {@code allowEmptyShould(true)}: mientras no haya clases en las features, ArchUnit fallaría por no
 * encontrar nada que analizar. Que los patrones estén bien escritos lo comprueba {@link ArquitecturaDetectaViolacionesTest}
 * con la feature de ejemplo {@code archejemplo}.
 */
@AnalyzeClasses(packagesOf = TurnosServiceApp.class, importOptions = DoNotIncludeTests.class)
class HexagonalArchitectureTest {

    static final String BASE = "ar.edu.um.programacion2.turnos.";

    static final String FEATURE_DOMAIN = BASE + "*.domain..";
    static final String FEATURE_APPLICATION = BASE + "*.application..";
    static final String FEATURE_INFRASTRUCTURE = BASE + "*.infrastructure..";

    // Lista blanca: todo lo que no está acá queda prohibido, incluida una librería que nadie pensó en prohibir.
    @ArchTest
    static final ArchRule domainSoloDependeDeJavaYDelDominio = classes()
        .that()
        .resideInAPackage(FEATURE_DOMAIN)
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage("java..", FEATURE_DOMAIN, "lombok..")
        .because("el dominio solo usa Java estándar, Lombok y clases de dominio")
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule domainNoDependeDeApplicationNiInfrastructure = noClasses()
        .that()
        .resideInAPackage(FEATURE_DOMAIN)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(FEATURE_APPLICATION, FEATURE_INFRASTRUCTURE)
        .because("las dependencias apuntan hacia el dominio; lo que necesite de afuera va como puerto en domain.ports.out")
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule applicationNoDependeDeInfrastructure = noClasses()
        .that()
        .resideInAPackage(FEATURE_APPLICATION)
        .should()
        .dependOnClassesThat()
        .resideInAPackage(FEATURE_INFRASTRUCTURE)
        .because("los use cases dependen de puertos, no de adapters")
        .allowEmptyShould(true);

    // Adaptadores de entrada: controllers, listener de Kafka y disparadores de CU-11 (arranque y periódico).
    static final String FEATURE_WEB = BASE + "*.infrastructure.web..";
    static final String FEATURE_LISTENER = BASE + "*.infrastructure.messaging.listener..";
    static final String FEATURE_STARTUP = BASE + "*.infrastructure.startup..";
    static final String FEATURE_PERIODIC = BASE + "*.infrastructure.periodic..";

    // Adaptadores de salida: base propia, API de la cátedra y servicio de catálogo, y productor de Kafka.
    static final String FEATURE_PERSISTENCE = BASE + "*.infrastructure.persistence..";
    static final String FEATURE_CLIENT = BASE + "*.infrastructure.client..";
    static final String FEATURE_PRODUCER = BASE + "*.infrastructure.messaging.producer..";

    /**
     * De cualquier feature a cualquier feature. En turnos, {@code messaging} tiene las dos direcciones: el listener de los
     * eventos de la cátedra (entrada) y el productor de {@code AdditionalInformationSubmitted} (salida). Por eso se
     * separan en {@code messaging.listener} y {@code messaging.producer}, y {@link #messagingSoloEnListenerOProducer}
     * impide que una clase quede suelta en {@code messaging}, donde esta regla no la vería.
     */
    @ArchTest
    static final ArchRule adaptadoresDeEntradaNoDependenDeAdaptadoresDeSalida = noClasses()
        .that()
        .resideInAnyPackage(FEATURE_WEB, FEATURE_LISTENER, FEATURE_STARTUP, FEATURE_PERIODIC)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(FEATURE_PERSISTENCE, FEATURE_CLIENT, FEATURE_PRODUCER)
        .because(
            "los adaptadores de entrada llaman al XxxService; las entidades, los repositorios, los clientes REST y el productor de Kafka no salen de sus paquetes"
        )
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule messagingSoloEnListenerOProducer = classes()
        .that()
        .resideInAPackage(BASE + "*.infrastructure.messaging..")
        .should()
        .resideInAnyPackage(FEATURE_LISTENER, FEATURE_PRODUCER)
        .because("en messaging hay entrada (listener) y salida (producer): una clase fuera de los dos no se sabe de qué lado está")
        .allowEmptyShould(true);

    /**
     * Regla "la feature {@code origen} no depende de la feature {@code destino}". Turnos tiene una sola feature
     * ({@code reservation}, decisión del #10), así que todavía no hay ninguna regla real con esta construcción. Queda como
     * método para cuando aparezca una segunda feature, y {@link ArquitecturaDetectaViolacionesTest} la prueba con las dos
     * features de ejemplo.
     */
    static ArchRule featureNoDependeDe(String origen, String destino) {
        return noClasses()
            .that()
            .resideInAPackage(BASE + origen + "..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage(BASE + destino + "..")
            .because("la feature " + destino + " usa a " + origen + "; la dependencia inversa va como puerto de salida de " + origen)
            .allowEmptyShould(true);
    }
}
