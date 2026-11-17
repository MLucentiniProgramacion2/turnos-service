package ar.edu.um.programacion2.turnos.reservation.application.usecases;

import static ar.edu.um.programacion2.turnos.reservation.application.usecases.ReservationProcesses.VENCE;
import static ar.edu.um.programacion2.turnos.reservation.application.usecases.ReservationProcesses.id;
import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** CU-08 con el puerto de persistencia en memoria. */
class GetReservationProcessUseCaseImplTest {

    private static final long ANA = 7;
    private static final long BETO = 8;

    private final FakeTransactions transacciones = new FakeTransactions();
    private final InMemoryReservationProcessRepository procesos = new InMemoryReservationProcessRepository(transacciones);
    private final GetReservationProcessUseCaseImpl casoDeUso = new GetReservationProcessUseCaseImpl(procesos, new ReadTransactions(transacciones));

    @ParameterizedTest
    @EnumSource(ReservationProcessStatus.class)
    void cu08_principal_devuelveElProcesoPropioTalComoEstaGuardadoEnCualquierEstado(ReservationProcessStatus estado) {
        procesos.put(ReservationProcesses.in(estado, ANA, LocalTime.of(10, 0), 1));

        assertThat(casoDeUso.get(ANA, id(1)))
            .hasValueSatisfying(proceso -> {
                assertThat(proceso.getId()).isEqualTo(id(1));
                assertThat(proceso.getStatus()).isEqualTo(estado);
            });
        assertThat(transacciones.opened()).isEqualTo(1);
    }

    @Test
    void cu08_2b_unProcesoDeOtroUsuarioEsLoMismoQueUnoQueNoExiste() {
        procesos.put(ReservationProcesses.in(ReservationProcessStatus.CONFIRMED, BETO, LocalTime.of(10, 0), 1));

        assertThat(casoDeUso.get(ANA, id(1))).isEmpty();
        assertThat(casoDeUso.get(ANA, id(99))).isEmpty();
        assertThat(casoDeUso.get(BETO, id(1))).isPresent();
    }

    @Test
    void cu08_noModificaNadaAunqueHayaPasadoExpiresAt() {
        // ReservationProcesses deja el hold vencido en VENCE; el caso de uso no mira el reloj ni cierra el proceso.
        procesos.put(ReservationProcesses.in(ReservationProcessStatus.PHONE_REQUESTED, ANA, LocalTime.of(10, 0), 1));
        ReservationProcess antes = procesos.get(id(1));

        assertThat(casoDeUso.get(ANA, id(1))).hasValueSatisfying(proceso -> assertThat(proceso.getStatus()).isEqualTo(ReservationProcessStatus.PHONE_REQUESTED));

        assertThat(procesos.writes()).isEmpty();
        assertThat(procesos.get(id(1)).getVersion()).isEqualTo(antes.getVersion());
        assertThat(antes.getHold().orElseThrow().expiresAt()).isEqualTo(VENCE);
    }
}
