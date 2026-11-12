package ar.edu.um.programacion2.turnos;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.turnos.archejemplo.application.ApplicationQueUsaInfrastructure;
import ar.edu.um.programacion2.turnos.archejemplo.domain.DominioQueUsaApplication;
import ar.edu.um.programacion2.turnos.archejemplo.domain.DominioQueUsaHibernate;
import ar.edu.um.programacion2.turnos.archejemplo.domain.DominioQueUsaInfrastructure;
import ar.edu.um.programacion2.turnos.archejemplo.domain.DominioQueUsaJackson3;
import ar.edu.um.programacion2.turnos.archejemplo.domain.DominioQueUsaJacksonAnotaciones;
import ar.edu.um.programacion2.turnos.archejemplo.domain.DominioQueUsaJakartaPersistence;
import ar.edu.um.programacion2.turnos.archejemplo.domain.DominioQueUsaLibreriaNoListada;
import ar.edu.um.programacion2.turnos.archejemplo.domain.DominioQueUsaSecurityDeJHipster;
import ar.edu.um.programacion2.turnos.archejemplo.domain.DominioQueUsaSpring;
import ar.edu.um.programacion2.turnos.archejemplo.infrastructure.FeatureQueUsaConfigDeJHipster;
import ar.edu.um.programacion2.turnos.archejemplo.infrastructure.FeatureQueUsaDomainDeJHipster;
import ar.edu.um.programacion2.turnos.archejemplo.infrastructure.FeatureQueUsaOtraFeature;
import ar.edu.um.programacion2.turnos.archejemplo.infrastructure.FeatureQueUsaRepositoryDeJHipster;
import ar.edu.um.programacion2.turnos.archejemplo.infrastructure.FeatureQueUsaServiceDeJHipster;
import ar.edu.um.programacion2.turnos.archejemplo.infrastructure.FeatureQueUsaWebDeJHipster;
import ar.edu.um.programacion2.turnos.archejemplo.infrastructure.messaging.MessagingSuelto;
import ar.edu.um.programacion2.turnos.archejemplo.infrastructure.messaging.listener.ListenerQueUsaPersistence;
import ar.edu.um.programacion2.turnos.archejemplo.infrastructure.messaging.listener.ListenerQueUsaProducer;
import ar.edu.um.programacion2.turnos.archejemplo.infrastructure.periodic.PeriodicQueUsaClient;
import ar.edu.um.programacion2.turnos.archejemplo.infrastructure.startup.StartupQueUsaPersistence;
import ar.edu.um.programacion2.turnos.archejemplo.infrastructure.web.WebQueUsaClient;
import ar.edu.um.programacion2.turnos.archejemplo.infrastructure.web.WebQueUsaPersistence;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Comprueba que las reglas reales de {@link HexagonalArchitectureTest} y {@link TechnicalStructureTest} detectan cada
 * violación de la feature de ejemplo {@code archejemplo} y no marcan las clases que cumplen.
 * <p>
 * Cada test compara el conjunto exacto de clases que violan la regla: si la regla dejara pasar una violación, o marcara
 * una clase que cumple, el conjunto no coincidiría.
 */
class ArquitecturaDetectaViolacionesTest {

    private static final String PAQUETE_EJEMPLO = "ar.edu.um.programacion2.turnos.archejemplo";
    private static final String PAQUETE_VECINA = "ar.edu.um.programacion2.turnos.archvecina";
    private static final String CARPETA_EJEMPLO = "/ar/edu/um/programacion2/turnos/archejemplo/";

    private static final JavaClasses EJEMPLO = new ClassFileImporter().importPackages(PAQUETE_EJEMPLO);

    @Test
    void domainSoloDependeDeJavaYDelDominioDetectaCadaDependenciaNoPermitida() {
        assertThat(clasesQueViolan(HexagonalArchitectureTest.domainSoloDependeDeJavaYDelDominio, EJEMPLO)).containsExactlyInAnyOrder(
            nombres(
                DominioQueUsaApplication.class,
                DominioQueUsaInfrastructure.class,
                DominioQueUsaSpring.class,
                DominioQueUsaJakartaPersistence.class,
                DominioQueUsaHibernate.class,
                DominioQueUsaJacksonAnotaciones.class,
                DominioQueUsaJackson3.class,
                DominioQueUsaSecurityDeJHipster.class,
                DominioQueUsaLibreriaNoListada.class
            )
        );
    }

    @Test
    void domainNoDependeDeApplicationNiInfrastructureDetectaLasDosDirecciones() {
        assertThat(
            clasesQueViolan(HexagonalArchitectureTest.domainNoDependeDeApplicationNiInfrastructure, EJEMPLO)
        ).containsExactlyInAnyOrder(nombres(DominioQueUsaApplication.class, DominioQueUsaInfrastructure.class));
    }

    @Test
    void applicationNoDependeDeInfrastructureDetectaLaViolacion() {
        assertThat(clasesQueViolan(HexagonalArchitectureTest.applicationNoDependeDeInfrastructure, EJEMPLO)).containsExactly(
            nombres(ApplicationQueUsaInfrastructure.class)
        );
    }

    @Test
    void adaptadoresDeEntradaNoDependenDeAdaptadoresDeSalidaDetectaPersistenceClientYProducer() {
        // El controller de ejemplo también está en infrastructure.web y no se marca: llama al service. El productor
        // tampoco: es de salida, y una dependencia de salida a salida está permitida.
        assertThat(
            clasesQueViolan(HexagonalArchitectureTest.adaptadoresDeEntradaNoDependenDeAdaptadoresDeSalida, EJEMPLO)
        ).containsExactlyInAnyOrder(
            nombres(
                WebQueUsaPersistence.class,
                WebQueUsaClient.class,
                ListenerQueUsaPersistence.class,
                ListenerQueUsaProducer.class,
                StartupQueUsaPersistence.class,
                PeriodicQueUsaClient.class
            )
        );
    }

    @Test
    void messagingSoloEnListenerOProducerDetectaLaClaseSuelta() {
        assertThat(clasesQueViolanSinDependencias(HexagonalArchitectureTest.messagingSoloEnListenerOProducer, EJEMPLO)).containsExactly(
            nombres(MessagingSuelto.class)
        );
    }

    @Test
    void featureNoDependeDeDetectaLaDependenciaEntreFeatures() {
        // Turnos tiene una sola feature real: la construcción se prueba entre las dos features de ejemplo.
        JavaClasses ejemploYVecina = new ClassFileImporter().importPackages(PAQUETE_EJEMPLO, PAQUETE_VECINA);

        assertThat(clasesQueViolan(HexagonalArchitectureTest.featureNoDependeDe("archejemplo", "archvecina"), ejemploYVecina)).containsExactly(
            nombres(FeatureQueUsaOtraFeature.class)
        );
    }

    @Test
    void reglaPorCapasDeJHipsterSoloMarcaLasFeaturesQueUsanWebDomainConfigRepositoryOService() {
        // Las capas no opcionales (Config, Web, Security, Domain) no pueden quedar vacías: se importa main junto
        // con la feature de ejemplo, que es la única parte de test-classes que se incluye.
        ImportOption mainYEjemplo = location -> new DoNotIncludeTests().includes(location) || location.contains(CARPETA_EJEMPLO);
        JavaClasses clases = new ClassFileImporter().withImportOption(mainYEjemplo).importPackagesOf(TurnosServiceApp.class);

        // PerfilUsuarioEjemploEntity (la entidad propia sobre jhi_user) no se marca: no depende de JHipster.
        assertThat(clasesQueViolan(TechnicalStructureTest.respectsTechnicalArchitectureLayers, clases)).containsExactlyInAnyOrder(
            nombres(
                FeatureQueUsaWebDeJHipster.class,
                FeatureQueUsaDomainDeJHipster.class,
                FeatureQueUsaConfigDeJHipster.class,
                FeatureQueUsaRepositoryDeJHipster.class,
                FeatureQueUsaServiceDeJHipster.class
            )
        );
    }

    @Test
    void lasReglasRealesNoAnalizanLaFeatureDeEjemplo() {
        // Mismo import que @AnalyzeClasses(packagesOf = TurnosServiceApp.class, importOptions = DoNotIncludeTests.class)
        JavaClasses reales = new ClassFileImporter().withImportOption(new DoNotIncludeTests()).importPackagesOf(TurnosServiceApp.class);

        assertThat(reales.contain(TurnosServiceApp.class)).isTrue();
        assertThat(reales.stream().map(JavaClass::getPackageName)).noneMatch(
            paquete -> paquete.startsWith(PAQUETE_EJEMPLO) || paquete.startsWith(PAQUETE_VECINA)
        );
    }

    /** Nombres de las clases de origen de las dependencias que violan la regla. */
    private static Set<String> clasesQueViolan(ArchRule regla, JavaClasses clases) {
        Set<String> origenes = new TreeSet<>();
        regla
            .evaluate(clases)
            .handleViolations((Collection<Dependency> dependencias, String mensaje) ->
                dependencias.forEach(dependencia -> origenes.add(dependencia.getOriginClass().getName()))
            );
        return origenes;
    }

    /** Para las reglas sobre dónde está una clase, que no hablan de dependencias: la violación es la clase misma. */
    private static Set<String> clasesQueViolanSinDependencias(ArchRule regla, JavaClasses clases) {
        Set<String> violadoras = new TreeSet<>();
        regla
            .evaluate(clases)
            .handleViolations((Collection<JavaClass> violaciones, String mensaje) ->
                violaciones.forEach(clase -> violadoras.add(clase.getName()))
            );
        return violadoras;
    }

    private static String[] nombres(Class<?>... clases) {
        return Arrays.stream(clases).map(Class::getName).toArray(String[]::new);
    }
}
