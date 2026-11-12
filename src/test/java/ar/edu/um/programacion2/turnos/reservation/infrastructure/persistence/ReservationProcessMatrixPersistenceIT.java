package ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.turnos.IntegrationTest;
import ar.edu.um.programacion2.turnos.repository.UserRepository;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessMatrix;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessMatrix.Caso;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessMatrix.Esperado;
import ar.edu.um.programacion2.turnos.reservation.domain.model.TransitionResult;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.mapper.ReservationProcessMapper;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.repository.JpaReservationProcessRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cada combinación de la matriz que <strong>cambia</strong> el proceso, guardada en PostgreSQL: si un {@code CHECK} de la
 * tabla contradice al modelo (por ejemplo, exige los datos del hold en un estado al que el modelo llega sin ellos), la
 * inserción falla acá. Cada caso corre en su transacción y se descarta al final.
 */
@IntegrationTest
@Transactional
class ReservationProcessMatrixPersistenceIT {

    @Autowired
    private JpaReservationProcessRepository repository;

    @Autowired
    private ReservationProcessMapper mapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager em;

    static List<Caso> casosAplicados() {
        return ReservationProcessMatrix.casos()
            .stream()
            .filter(caso -> caso.esperado() instanceof Esperado.Aplica)
            .toList();
    }

    @Test
    void hayCasosAplicadosDesdeCadaIntermedioYDesdeConfirmed() {
        assertThat(casosAplicados()).hasSize(31);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("casosAplicados")
    void elResultadoDeCadaTransicionAplicadaSeGuardaYSeLeeIgual(Caso caso) {
        long userId = userRepository.saveAndFlush(ReservationTestDatabase.usuario("reserva-matriz")).getId();
        ReservationProcess proceso = ReservationProcessMatrix.procesoEn(caso.estado(), userId);
        assertThat(caso.operacion().aplicar(proceso)).isInstanceOf(TransitionResult.Applied.class);

        repository.saveAndFlush(mapper.toEntity(proceso));
        em.clear();

        ReservationProcess leido = mapper.toDomain(repository.findById(proceso.getId().value()).orElseThrow());
        assertThat(leido).usingRecursiveComparison().ignoringFields("version").isEqualTo(proceso);
    }
}
