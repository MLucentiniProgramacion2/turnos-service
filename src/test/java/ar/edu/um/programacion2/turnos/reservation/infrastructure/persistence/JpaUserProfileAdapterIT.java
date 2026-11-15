package ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence;

import static ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.ReservationTestDatabase.usuario;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.um.programacion2.turnos.IntegrationTest;
import ar.edu.um.programacion2.turnos.domain.User;
import ar.edu.um.programacion2.turnos.repository.UserRepository;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PatientName;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ProcessOwner;
import ar.edu.um.programacion2.turnos.reservation.domain.model.UserProfile;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.UserProfileProvider;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.entity.UserProfileEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.Attribute;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * El perfil del usuario se lee con una entidad propia de solo lectura sobre {@code jhi_user} (decisión del #10). El
 * contexto de este test arranca con {@code ddl-auto: validate}: si la entidad nombrara una columna que no existe, no
 * levantaría (lo comprueba la mutación de la explicación).
 */
@IntegrationTest
class JpaUserProfileAdapterIT {

    @Autowired
    private UserProfileProvider perfiles;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private EntityManager em;

    @BeforeEach
    @AfterEach
    void limpiar() {
        ReservationTestDatabase.limpiar(tx, em);
    }

    @Test
    void leeElLoginYLosNombresDelUsuarioDeJHipster() {
        long id = guardar(usuario("reserva-perfil"));

        UserProfile perfil = buscar(id).orElseThrow();

        assertThat(perfil.owner()).isEqualTo(new ProcessOwner(id, "reserva-perfil"));
        assertThat(perfil.patientName()).contains(new PatientName("Ana", "Pérez"));
    }

    @Test
    void unUsuarioQueNoExisteDaVacio() {
        assertThat(buscar(999_999)).isEmpty();
    }

    @Test
    void unPerfilSinNombreOConUnNombreDeUnaLetraNoSirveComoPaciente() {
        User sinNombre = usuario("reserva-sin-nombre");
        sinNombre.setFirstName(null);
        User nombreCorto = usuario("reserva-corto");
        nombreCorto.setLastName("P");

        assertThat(buscar(guardar(sinNombre)).orElseThrow().patientName()).isEmpty();
        assertThat(buscar(guardar(nombreCorto)).orElseThrow().patientName()).isEmpty();
    }

    @Test
    void veElCambioQueHaceJHipsterEnElUsuario() {
        long id = guardar(usuario("reserva-cambia"));
        tx.executeWithoutResult(status -> {
            User user = userRepository.findById(id).orElseThrow();
            user.setLogin("reserva-cambiado");
            user.setLastName("Gómez");
        });

        UserProfile perfil = buscar(id).orElseThrow();

        assertThat(perfil.login()).isEqualTo("reserva-cambiado");
        assertThat(perfil.patientName()).contains(new PatientName("Ana", "Gómez"));
    }

    @Test
    void laEntidadSoloTieneLasCuatroColumnasQueNecesitaLaReserva() {
        assertThat(em.getMetamodel().entity(UserProfileEntity.class).getAttributes())
            .extracting(Attribute::getName)
            .containsExactlyInAnyOrder("id", "login", "firstName", "lastName");
    }

    @Test
    void elPerfilNoMuestraLosNombresEnSuToString() {
        long id = guardar(usuario("reserva-privado"));

        assertThat(buscar(id).orElseThrow().toString()).doesNotContain("Ana").doesNotContain("Pérez");
        String entidad = tx.execute(status -> em.find(UserProfileEntity.class, id).toString());
        assertThat(entidad).doesNotContain("Ana").doesNotContain("Pérez");
    }

    @Test
    void sinUnaTransaccionAbiertaElAdaptadorNoTrabaja() {
        assertThatThrownBy(() -> perfiles.findById(1)).isInstanceOf(IllegalTransactionStateException.class);
    }

    private long guardar(User user) {
        return tx.execute(status -> userRepository.saveAndFlush(user).getId());
    }

    private Optional<UserProfile> buscar(long id) {
        return tx.execute(status -> perfiles.findById(id));
    }
}
