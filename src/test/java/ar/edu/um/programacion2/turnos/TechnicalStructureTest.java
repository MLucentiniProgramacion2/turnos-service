package ar.edu.um.programacion2.turnos;

import static ar.edu.um.programacion2.turnos.HexagonalArchitectureTest.FEATURE_APPLICATION;
import static ar.edu.um.programacion2.turnos.HexagonalArchitectureTest.FEATURE_DOMAIN;
import static ar.edu.um.programacion2.turnos.HexagonalArchitectureTest.FEATURE_INFRASTRUCTURE;
import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packagesOf = TurnosServiceApp.class, importOptions = DoNotIncludeTests.class)
class TechnicalStructureTest {

    // Las capas de JHipster están ancladas al paquete base (antes eran "..web..", "..domain..", etc.) para que
    // los paquetes de las features no caigan en ellas: reservation.infrastructure.web no es la capa Web de JHipster
    // ni reservation.domain es su capa Domain. Las features son una capa más, opcional mientras no tengan clases,
    // que solo puede usar Security (y Constants y ApplicationProperties, ignorados abajo para todos).
    // El perfil del usuario se lee con una entidad propia de solo lectura sobre jhi_user, no con User,
    // UserRepository ni UserService (decisión del #10): por eso Persistence, Service y Domain no se abren a Features.
    // prettier-ignore
    @ArchTest
    static final ArchRule respectsTechnicalArchitectureLayers = layeredArchitecture()
        .consideringAllDependencies()
        .layer("Config").definedBy("ar.edu.um.programacion2.turnos.config..")
        .layer("Web").definedBy("ar.edu.um.programacion2.turnos.web..")
        .optionalLayer("Service").definedBy("ar.edu.um.programacion2.turnos.service..")
        .layer("Security").definedBy("ar.edu.um.programacion2.turnos.security..")
        .optionalLayer("Persistence").definedBy("ar.edu.um.programacion2.turnos.repository..")
        .layer("Domain").definedBy("ar.edu.um.programacion2.turnos.domain..")
        .optionalLayer("Features").definedBy(FEATURE_DOMAIN, FEATURE_APPLICATION, FEATURE_INFRASTRUCTURE)

        .whereLayer("Config").mayNotBeAccessedByAnyLayer()
        .whereLayer("Web").mayOnlyBeAccessedByLayers("Config")
        .whereLayer("Service").mayOnlyBeAccessedByLayers("Web", "Config")
        .whereLayer("Security").mayOnlyBeAccessedByLayers("Config", "Service", "Web", "Features")
        .whereLayer("Persistence").mayOnlyBeAccessedByLayers("Service", "Security", "Web", "Config")
        .whereLayer("Domain").mayOnlyBeAccessedByLayers("Persistence", "Service", "Security", "Web", "Config")

        .ignoreDependency(belongToAnyOf(TurnosServiceApp.class), alwaysTrue())
        .ignoreDependency(alwaysTrue(), belongToAnyOf(
            ar.edu.um.programacion2.turnos.config.Constants.class,
            ar.edu.um.programacion2.turnos.config.ApplicationProperties.class
        ));
}
