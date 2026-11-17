package ar.edu.um.programacion2.turnos.reservation.application.usecases;

import static ar.edu.um.programacion2.turnos.reservation.application.usecases.ReservationProcesses.ANTES;
import static ar.edu.um.programacion2.turnos.reservation.application.usecases.ReservationProcesses.id;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.CANCELLED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.CONFIRMED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.CREATING_HOLD;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.EXPIRED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.FAILED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.HOLD_CREATED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.PHONE_REQUESTED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.PHONE_SUBMITTED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.WAITING_FOR_PHONE_REQUEST;
import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraReservation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraReservationList;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraReservationStatus;
import ar.edu.um.programacion2.turnos.reservation.domain.model.Confirmation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.MyReservations;
import ar.edu.um.programacion2.turnos.reservation.domain.model.MyReservationsQuery;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PageQuery;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.AppointmentGateway;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraAccessDeniedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraErrorCode;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraInvalidResponseException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraOutcomeUnknownException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraRejectedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraUnavailableException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.OptimisticLockingFailureException;

/** CU-09 con los puertos simulados: un test por flujo alternativo, nombrado con su número. */
@ExtendWith(OutputCaptureExtension.class)
class ListMyReservationsUseCaseImplTest {

    private static final long ANA = 7;
    private static final long BETO = 8;
    private static final Instant AHORA = Instant.parse("2026-12-14T13:00:00Z");
    private static final Instant CONFIRMADA_EN = Instant.parse("2026-12-14T12:25:00Z");
    private static final Instant CANCELADA_EN = Instant.parse("2026-12-14T12:50:00Z");

    private final FakeTransactions transacciones = new FakeTransactions();
    private final InMemoryReservationProcessRepository procesos = new InMemoryReservationProcessRepository(transacciones);
    private final Catedra catedra = new Catedra();
    private final ListMyReservationsUseCaseImpl casoDeUso = new ListMyReservationsUseCaseImpl(
        procesos,
        new ReadTransactions(transacciones),
        catedra,
        new ReservationProcessUpdater(procesos, transacciones),
        Clock.fixed(AHORA, ZoneOffset.UTC)
    );

    /** La cátedra simulada: anota cada pedido y comprueba que ninguno se haga adentro de una transacción. */
    private final class Catedra implements AppointmentGateway {

        record Pedido(String externalPatientId, LocalDate from, LocalDate to) {}

        final List<Pedido> pedidos = new ArrayList<>();
        CatedraReservationList respuesta = new CatedraReservationList(List.of(), 0);
        CatedraException falla;
        Runnable mientrasTanto = () -> {};

        @Override
        public CatedraReservationList listReservations(String externalPatientId, LocalDate from, LocalDate to) {
            assertThat(transacciones.isActive()).as("ningún pedido a la cátedra adentro de una transacción").isFalse();
            pedidos.add(new Pedido(externalPatientId, from, to));
            mientrasTanto.run();
            if (falla != null) {
                throw falla;
            }
            return respuesta;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Flujo principal
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void cu09_principal_unaLlamadaPorPaginaYConvergeHaciaAdelanteLoQueLaCatedraInforma() {
        procesos.put(ReservationProcesses.in(PHONE_SUBMITTED, ANA, LocalDate.parse("2026-12-15"), LocalTime.of(10, 0), 1));
        procesos.put(ReservationProcesses.in(CONFIRMED, ANA, LocalDate.parse("2026-12-17"), LocalTime.of(9, 0), 2));
        procesos.put(ReservationProcesses.in(EXPIRED, ANA, LocalDate.parse("2026-12-20"), LocalTime.of(9, 0), 3));
        catedra.respuesta = lista(
            reserva(1, CatedraReservationStatus.CONFIRMED, CONFIRMADA_EN, null),
            reserva(2, CatedraReservationStatus.CANCELLED, CONFIRMADA_EN, CANCELADA_EN),
            reserva(3, CatedraReservationStatus.CONFIRMED, CONFIRMADA_EN, null)
        );

        MyReservations resultado = casoDeUso.list(todas(ANA));

        assertThat(resultado.catedraUpToDate()).isTrue();
        // Orden: el turno más lejano primero.
        assertThat(resultado.page().items()).extracting(ReservationProcess::getStatus).containsExactly(EXPIRED, CANCELLED, CONFIRMED);
        assertThat(resultado.page().totalElements()).isEqualTo(3);
        ReservationProcess confirmado = procesos.get(id(1));
        assertThat(confirmado.getStatus()).isEqualTo(CONFIRMED);
        assertThat(confirmado.getConfirmation()).contains(new Confirmation(8251, CONFIRMADA_EN));
        // El que ya estaba confirmado conserva su confirmación (la del evento).
        assertThat(procesos.get(id(2)).getConfirmation()).contains(new Confirmation(8251, ANTES));
        // Un final no cambia aunque la cátedra diga otra cosa, y no se pregunta por él: el rango es el de los otros dos.
        assertThat(procesos.get(id(3)).getStatus()).isEqualTo(EXPIRED);
        assertThat(catedra.pedidos).containsExactly(new Catedra.Pedido("user-7", LocalDate.parse("2026-12-15"), LocalDate.parse("2026-12-17")));
    }

    @Test
    void cu09_7_losDatosQueInformaLaCatedraSalenDelProcesoConvergido() {
        procesos.put(ReservationProcesses.in(PHONE_SUBMITTED, ANA, LocalTime.of(10, 0), 1));
        catedra.respuesta = lista(reserva(1, CatedraReservationStatus.CANCELLED, CONFIRMADA_EN, CANCELADA_EN));

        ReservationProcess proceso = casoDeUso.list(todas(ANA)).page().items().getFirst();

        assertThat(proceso.getStatus()).isEqualTo(CANCELLED);
        assertThat(proceso.getConfirmation()).contains(new Confirmation(8251, CONFIRMADA_EN));
        assertThat(proceso.getCancellation().orElseThrow().cancelledAt()).isEqualTo(CANCELADA_EN);
        assertThat(proceso.getCancellation().orElseThrow().reason()).isEqualTo("Cambio de planes");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Alternativos
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void cu09_3a_sinProcesosEsUnaListaVaciaSinLlamarALaCatedra() {
        procesos.put(ReservationProcesses.in(CONFIRMED, BETO, LocalTime.of(10, 0), 1));

        MyReservations resultado = casoDeUso.list(todas(ANA));

        assertThat(resultado.page().items()).isEmpty();
        assertThat(resultado.page().totalElements()).isZero();
        assertThat(resultado.catedraUpToDate()).isTrue();
        assertThat(catedra.pedidos).isEmpty();
    }

    @Test
    void cu09_3a_ningunoCumpleElFiltroEsListaVaciaSinLlamar() {
        procesos.put(ReservationProcesses.in(CONFIRMED, ANA, LocalTime.of(10, 0), 1));

        assertThat(casoDeUso.list(query(ANA, Set.of(CANCELLED), 0, 20)).page().items()).isEmpty();
        assertThat(catedra.pedidos).isEmpty();
    }

    @Test
    void cu09_4a_sinReservationProcessIdNoSeLlamaALaCatedra() {
        procesos.put(ReservationProcesses.in(CREATING_HOLD, ANA, LocalTime.of(10, 0), 1));

        MyReservations resultado = casoDeUso.list(todas(ANA));

        assertThat(resultado.page().items()).extracting(ReservationProcess::getStatus).containsExactly(CREATING_HOLD);
        assertThat(resultado.catedraUpToDate()).isTrue();
        assertThat(catedra.pedidos).isEmpty();
    }

    @Test
    void cu09_4a_unaPaginaSoloDeFinalesNoLlamaALaCatedra() {
        // Un final nunca cambia: preguntar por él no aporta nada (4a ampliado en el #14).
        procesos.put(ReservationProcesses.in(EXPIRED, ANA, LocalTime.of(9, 0), 1));
        procesos.put(ReservationProcesses.in(CANCELLED, ANA, LocalTime.of(10, 0), 2));
        procesos.put(ReservationProcesses.in(FAILED, ANA, LocalTime.of(11, 0), 3));

        assertThat(casoDeUso.list(todas(ANA)).catedraUpToDate()).isTrue();
        assertThat(catedra.pedidos).isEmpty();
    }

    static Stream<CatedraException> fallasDeLaCatedra() {
        return Stream.of(
            new CatedraUnavailableException("no responde"),
            new CatedraOutcomeUnknownException("no debería pasar en un GET"),
            new CatedraInvalidResponseException("sin X-Total-Count"),
            new CatedraAccessDeniedException("401"),
            new CatedraAccessDeniedException("403 STUDENT_INTEGRATION_NOT_FOUND"),
            new CatedraRejectedException(400, CatedraErrorCode.INVALID_DATE_RANGE, "400")
        );
    }

    @ParameterizedTest
    @MethodSource("fallasDeLaCatedra")
    void cu09_4b_4c_cualquierFallaDeLaCatedraDaLosDatosLocalesMarcadosComoNoActualizados(CatedraException falla, CapturedOutput salida) {
        procesos.put(ReservationProcesses.in(PHONE_SUBMITTED, ANA, LocalTime.of(10, 0), 1));
        catedra.falla = falla;

        MyReservations resultado = casoDeUso.list(todas(ANA));

        assertThat(resultado.catedraUpToDate()).isFalse();
        assertThat(resultado.page().items()).extracting(ReservationProcess::getStatus).containsExactly(PHONE_SUBMITTED);
        assertThat(procesos.writes()).isEmpty();
        // Sin reintentos: el usuario está esperando.
        assertThat(catedra.pedidos).hasSize(1);
        // WARN si es transitorio; ERROR si es nuestro (credencial, pedido mal armado o respuesta fuera de contrato).
        String nivel = falla instanceof CatedraUnavailableException ? " WARN " : " ERROR ";
        assertThat(salida.getOut()).contains(nivel).contains("CU-09");
    }

    @Test
    void cu09_5a_unProcesoQueNoApareceEnLaCatedraSeMuestraConElEstadoLocal() {
        procesos.put(ReservationProcesses.in(PHONE_SUBMITTED, ANA, LocalTime.of(10, 0), 1));

        MyReservations resultado = casoDeUso.list(todas(ANA));

        assertThat(resultado.page().items()).extracting(ReservationProcess::getStatus).containsExactly(PHONE_SUBMITTED);
        assertThat(resultado.catedraUpToDate()).isTrue();
        assertThat(procesos.writes()).isEmpty();
    }

    @Test
    void cu09_5_unaReservaDeLaCatedraSinProcesoLocalNoSeMuestraNiSeCrea() {
        procesos.put(ReservationProcesses.in(CONFIRMED, ANA, LocalTime.of(10, 0), 1));
        // La 99 es de la cuenta técnica, con el mismo externalPatientId (por ejemplo, de una base local reseteada).
        catedra.respuesta = lista(reserva(1, CatedraReservationStatus.CONFIRMED, CONFIRMADA_EN, null), reserva(99, CatedraReservationStatus.CONFIRMED, CONFIRMADA_EN, null));

        MyReservations resultado = casoDeUso.list(todas(ANA));

        assertThat(resultado.page().items()).extracting(proceso -> proceso.getId()).containsExactly(id(1));
        assertThat(procesos.all()).hasSize(1);
    }

    @Test
    void cu09_5_unaReservaConElMismoProcessIdPeroOtroPacienteNoSeCruza(CapturedOutput salida) {
        procesos.put(ReservationProcesses.in(PHONE_SUBMITTED, ANA, LocalTime.of(10, 0), 1));
        catedra.respuesta = lista(
            new CatedraReservation("process-1", "user-8", 8251L, LocalDate.parse("2026-12-15"), LocalTime.of(10, 0), CatedraReservationStatus.CANCELLED, CONFIRMADA_EN, CANCELADA_EN, null)
        );

        casoDeUso.list(todas(ANA));

        assertThat(procesos.get(id(1)).getStatus()).isEqualTo(PHONE_SUBMITTED);
        assertThat(salida.getOut()).contains("no es del dueño");
    }

    @Test
    void cu09_6a_phonePendingYFailedNoCambianElEstadoLocal() {
        procesos.put(ReservationProcesses.in(WAITING_FOR_PHONE_REQUEST, ANA, LocalTime.of(10, 0), 1));
        procesos.put(ReservationProcesses.in(CONFIRMED, ANA, LocalTime.of(11, 0), 2));
        catedra.respuesta = lista(reserva(1, CatedraReservationStatus.PHONE_PENDING, null, null), reserva(2, CatedraReservationStatus.FAILED, null, null));

        MyReservations resultado = casoDeUso.list(todas(ANA));

        assertThat(resultado.page().items()).extracting(ReservationProcess::getStatus).containsExactly(CONFIRMED, WAITING_FOR_PHONE_REQUEST);
        assertThat(procesos.writes()).isEmpty();
    }

    @Test
    void cu09_6b_siUnEventoMovioElProcesoMientrasTantoSeRespondeElEstadoActual() {
        procesos.put(ReservationProcesses.in(PHONE_SUBMITTED, ANA, LocalTime.of(10, 0), 1));
        catedra.respuesta = lista(reserva(1, CatedraReservationStatus.CONFIRMED, CONFIRMADA_EN, null));
        // AppointmentConfirmed llega por Kafka mientras se esperaba a la cátedra: lo cierra con su propia confirmación.
        catedra.mientrasTanto = () -> procesos.changeFromOutside(id(1), proceso -> {
            proceso.confirmed(new Confirmation(8251, ANTES), ANTES);
            return proceso;
        });

        MyReservations resultado = casoDeUso.list(todas(ANA));

        ReservationProcess respondido = resultado.page().items().getFirst();
        assertThat(respondido.getStatus()).isEqualTo(CONFIRMED);
        assertThat(respondido.getConfirmation()).contains(new Confirmation(8251, ANTES));
        assertThat(resultado.catedraUpToDate()).isTrue();
        assertThat(procesos.writes()).isEmpty();
    }

    @Test
    void cu09_6b_siElGuardadoChocaEnLosTresIntentosSeMuestraElLocalYNoEstaActualizado() {
        procesos.put(ReservationProcesses.in(PHONE_SUBMITTED, ANA, LocalTime.of(10, 0), 1));
        procesos.put(ReservationProcesses.in(CONFIRMED, ANA, LocalTime.of(11, 0), 2));
        catedra.respuesta = lista(reserva(1, CatedraReservationStatus.CONFIRMED, CONFIRMADA_EN, null), reserva(2, CatedraReservationStatus.CANCELLED, CONFIRMADA_EN, CANCELADA_EN));
        procesos.failNextSaves(3, new OptimisticLockingFailureException("otra transacción lo cambió"));

        MyReservations resultado = casoDeUso.list(todas(ANA));

        assertThat(resultado.catedraUpToDate()).isFalse();
        // El primero (el de las 11:00) no se pudo guardar y se muestra como se leyó; el segundo siguió y convergió.
        assertThat(resultado.page().items()).extracting(ReservationProcess::getStatus).containsExactly(CONFIRMED, CONFIRMED);
        assertThat(procesos.get(id(2)).getStatus()).isEqualTo(CONFIRMED);
        assertThat(procesos.get(id(1)).getStatus()).isEqualTo(CONFIRMED);
    }

    @Test
    void elFiltroSeAplicaAntesDeConvergerYSeRespondeElEstadoActual() {
        procesos.put(ReservationProcesses.in(CONFIRMED, ANA, LocalTime.of(10, 0), 1));
        procesos.put(ReservationProcesses.in(EXPIRED, ANA, LocalTime.of(11, 0), 2));
        catedra.respuesta = lista(reserva(1, CatedraReservationStatus.CANCELLED, CONFIRMADA_EN, CANCELADA_EN));

        MyReservations resultado = casoDeUso.list(query(ANA, Set.of(CONFIRMED), 0, 20));

        assertThat(resultado.page().items()).extracting(ReservationProcess::getStatus).containsExactly(CANCELLED);
        assertThat(resultado.page().totalElements()).isEqualTo(1);
    }

    @Test
    void laPaginaYElTotalRespetanElOrdenYLosProcesosDeOtros() {
        for (int n = 1; n <= 5; n++) {
            procesos.put(ReservationProcesses.in(EXPIRED, ANA, LocalTime.of(8 + n, 0), n));
        }
        procesos.put(ReservationProcesses.in(EXPIRED, BETO, LocalTime.of(20, 0), 6));

        MyReservations segunda = casoDeUso.list(query(ANA, Set.of(), 1, 2));

        assertThat(segunda.page().items()).extracting(proceso -> proceso.getId()).containsExactly(id(3), id(2));
        assertThat(segunda.page().totalElements()).isEqualTo(5);
        assertThat(segunda.page().totalPages()).isEqualTo(3);
    }

    @Test
    void unDatoIncompletoDeLaCatedraSeAplicaIgualConUnaAdvertencia(CapturedOutput salida) {
        procesos.put(ReservationProcesses.in(PHONE_REQUESTED, ANA, LocalTime.of(10, 0), 1));
        catedra.respuesta = lista(reserva(1, CatedraReservationStatus.CONFIRMED, null, null));

        casoDeUso.list(todas(ANA));

        assertThat(procesos.get(id(1)).getConfirmation()).contains(new Confirmation(8251, AHORA));
        assertThat(salida.getOut()).contains("CONFIRMED_AT_MISSING");
    }

    @Test
    void unHoldCreatedQueLaCatedraConfirmoTambienConverge() {
        procesos.put(ReservationProcesses.in(HOLD_CREATED, ANA, LocalTime.of(10, 0), 1));
        catedra.respuesta = lista(reserva(1, CatedraReservationStatus.CONFIRMED, CONFIRMADA_EN, null));

        casoDeUso.list(todas(ANA));

        assertThat(procesos.get(id(1)).getStatus()).isEqualTo(CONFIRMED);
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static MyReservationsQuery todas(long userId) {
        return query(userId, Set.of(), 0, 20);
    }

    private static MyReservationsQuery query(long userId, Set<ReservationProcessStatus> estados, int pagina, int tamano) {
        return new MyReservationsQuery(userId, estados, new PageQuery(pagina, tamano));
    }

    private static CatedraReservationList lista(CatedraReservation... reservas) {
        return new CatedraReservationList(List.of(reservas), 0);
    }

    private static CatedraReservation reserva(int n, CatedraReservationStatus estado, Instant confirmedAt, Instant cancelledAt) {
        return new CatedraReservation(
            ReservationProcesses.reservationProcessId(n),
            "user-" + ANA,
            8251L,
            LocalDate.parse("2026-12-15"),
            LocalTime.of(10, 0),
            estado,
            confirmedAt,
            cancelledAt,
            estado == CatedraReservationStatus.CANCELLED ? "Cambio de planes" : null
        );
    }
}
