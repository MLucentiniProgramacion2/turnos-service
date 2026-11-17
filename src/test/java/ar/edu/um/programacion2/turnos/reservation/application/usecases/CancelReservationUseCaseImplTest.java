package ar.edu.um.programacion2.turnos.reservation.application.usecases;

import static ar.edu.um.programacion2.turnos.reservation.application.usecases.ReservationProcesses.ANTES;
import static ar.edu.um.programacion2.turnos.reservation.application.usecases.ReservationProcesses.id;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.CANCELLED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.CONFIRMED;
import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.turnos.reservation.domain.model.CancelReservationCommand;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CancelReservationResult;
import ar.edu.um.programacion2.turnos.reservation.domain.model.Cancellation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraCancellation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraReservationList;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

/** CU-10 con los puertos simulados: un test por flujo alternativo, nombrado con su número. */
@ExtendWith(OutputCaptureExtension.class)
class CancelReservationUseCaseImplTest {

    private static final long ANA = 7;
    private static final long BETO = 8;
    private static final Instant AHORA = Instant.parse("2026-12-16T09:00:05Z");
    private static final Instant CANCELADA_EN = Instant.parse("2026-12-16T09:00:00Z");
    private static final String MOTIVO = "Me surgió un viaje imprevisto";

    private final FakeTransactions transacciones = new FakeTransactions();
    private final InMemoryReservationProcessRepository procesos = new InMemoryReservationProcessRepository(transacciones);
    private final Catedra catedra = new Catedra();
    private final CancelReservationUseCaseImpl casoDeUso = new CancelReservationUseCaseImpl(
        procesos,
        new ReadTransactions(transacciones),
        catedra,
        new ReservationProcessUpdater(procesos, transacciones),
        Clock.fixed(AHORA, ZoneOffset.UTC)
    );

    /** La cátedra simulada: anota cada cancelación y comprueba que ninguna se pida adentro de una transacción. */
    private final class Catedra implements AppointmentGateway {

        record Pedido(String reservationProcessId, String reason) {}

        final List<Pedido> pedidos = new ArrayList<>();
        CatedraException falla;
        Runnable mientrasTanto = () -> {};

        @Override
        public CatedraReservationList listReservations(String externalPatientId, LocalDate from, LocalDate to) {
            throw new AssertionError("CU-10 no lista reservas");
        }

        @Override
        public CatedraCancellation cancel(String reservationProcessId, String reason) {
            assertThat(transacciones.isActive()).as("ningún pedido a la cátedra adentro de una transacción").isFalse();
            pedidos.add(new Pedido(reservationProcessId, reason));
            mientrasTanto.run();
            if (falla != null) {
                throw falla;
            }
            return new CatedraCancellation(reservationProcessId, 8251L, CANCELADA_EN);
        }
    }

    @Test
    void cu10_principal_cancelaEnLaCatedraYGuardaElMotivoDelUsuarioConElInstanteDeLaCatedra() {
        procesos.put(ReservationProcesses.in(CONFIRMED, ANA, LocalTime.of(10, 0), 1));

        CancelReservationResult resultado = casoDeUso.cancel(new CancelReservationCommand(ANA, id(1), MOTIVO));

        assertThat(resultado).isInstanceOfSatisfying(CancelReservationResult.Cancelled.class, cancelado -> {
            assertThat(cancelado.process().getStatus()).isEqualTo(CANCELLED);
            assertThat(cancelado.process().getCancellation()).contains(new Cancellation(MOTIVO, CANCELADA_EN));
        });
        assertThat(catedra.pedidos).containsExactly(new Catedra.Pedido("process-1", MOTIVO));
        assertThat(procesos.writes()).containsExactly("save CANCELLED");
        ReservationProcess guardado = procesos.get(id(1));
        // El instante del cambio de estado es el de turnos; el de la cancelación, el de la cátedra.
        assertThat(guardado.getStatusChangedAt()).isEqualTo(AHORA);
        assertThat(guardado.getConfirmation()).isPresent();
    }

    @Test
    void cu10_sinMotivoSeCancelaIgual() {
        procesos.put(ReservationProcesses.in(CONFIRMED, ANA, LocalTime.of(10, 0), 1));

        casoDeUso.cancel(new CancelReservationCommand(ANA, id(1), "   "));

        assertThat(catedra.pedidos).containsExactly(new Catedra.Pedido("process-1", null));
        assertThat(procesos.get(id(1)).getCancellation()).contains(new Cancellation(null, CANCELADA_EN));
    }

    @Test
    void cu10_3a_unProcesoAjenoOInexistenteEsNotFoundYNoSeLlamaALaCatedra() {
        procesos.put(ReservationProcesses.in(CONFIRMED, BETO, LocalTime.of(10, 0), 1));

        assertThat(casoDeUso.cancel(new CancelReservationCommand(ANA, id(1), null))).isEqualTo(new CancelReservationResult.NotFound());
        assertThat(casoDeUso.cancel(new CancelReservationCommand(ANA, id(99), null))).isEqualTo(new CancelReservationResult.NotFound());
        assertThat(catedra.pedidos).isEmpty();
        assertThat(procesos.get(id(1)).getStatus()).isEqualTo(CONFIRMED);
    }

    @Test
    void cu10_4a_yaCanceladoResponde200SinLlamarALaCatedra(CapturedOutput salida) {
        procesos.put(ReservationProcesses.in(CANCELLED, ANA, LocalTime.of(10, 0), 1));

        CancelReservationResult resultado = casoDeUso.cancel(new CancelReservationCommand(ANA, id(1), MOTIVO));

        assertThat(resultado).isInstanceOfSatisfying(CancelReservationResult.Cancelled.class, cancelado ->
            assertThat(cancelado.process().getStatus()).isEqualTo(CANCELLED)
        );
        assertThat(catedra.pedidos).isEmpty();
        assertThat(procesos.writes()).isEmpty();
        assertThat(salida.getOut()).contains("ya estaba cancelado: no se llama a la cátedra");
    }

    @ParameterizedTest
    @EnumSource(value = ReservationProcessStatus.class, names = { "CONFIRMED", "CANCELLED" }, mode = EnumSource.Mode.EXCLUDE)
    void cu10_4b_otroEstadoNoSeCancelaYNoSeLlamaALaCatedra(ReservationProcessStatus estado) {
        procesos.put(ReservationProcesses.in(estado, ANA, LocalTime.of(10, 0), 1));

        assertThat(casoDeUso.cancel(new CancelReservationCommand(ANA, id(1), null))).isEqualTo(new CancelReservationResult.NotCancellable(estado));
        assertThat(catedra.pedidos).isEmpty();
        assertThat(procesos.writes()).isEmpty();
    }

    static Stream<Arguments> fallas() {
        return Stream.of(
            Arguments.of("5a sin conectar", new CatedraUnavailableException("no conectó"), new CancelReservationResult.CatedraUnavailable()),
            Arguments.of("5a resultado desconocido", new CatedraOutcomeUnknownException("timeout"), new CancelReservationResult.OutcomeUnknown()),
            Arguments.of(
                "6a no cancelable en la cátedra",
                new CatedraRejectedException(409, CatedraErrorCode.RESERVATION_NOT_CANCELLABLE, "409"),
                new CancelReservationResult.NotCancellable(CONFIRMED)
            ),
            Arguments.of(
                "6b no existe en la cátedra",
                new CatedraRejectedException(404, CatedraErrorCode.RESERVATION_NOT_FOUND, "404"),
                new CancelReservationResult.InternalError()
            ),
            Arguments.of(
                "6b de otro groupId",
                new CatedraRejectedException(403, CatedraErrorCode.RESERVATION_OWNER_MISMATCH, "403"),
                new CancelReservationResult.InternalError()
            ),
            Arguments.of(
                "6b validación de la cátedra",
                new CatedraRejectedException(400, CatedraErrorCode.VALIDATION_ERROR, "400"),
                new CancelReservationResult.InternalError()
            ),
            Arguments.of("6b credencial", new CatedraAccessDeniedException("401"), new CancelReservationResult.CatedraUnavailable()),
            Arguments.of("6b fuera de contrato", new CatedraInvalidResponseException("3xx"), new CancelReservationResult.UpstreamInvalidResponse())
        );
    }

    @ParameterizedTest(name = "cu10_{0}")
    @MethodSource("fallas")
    void cu10_5a_6a_6b_ningunaFallaCambiaElProcesoNiSeReintenta(String caso, CatedraException falla, CancelReservationResult esperado, CapturedOutput salida) {
        procesos.put(ReservationProcesses.in(CONFIRMED, ANA, LocalTime.of(10, 0), 1));
        catedra.falla = falla;

        assertThat(casoDeUso.cancel(new CancelReservationCommand(ANA, id(1), MOTIVO))).isEqualTo(esperado);

        assertThat(catedra.pedidos).hasSize(1);
        assertThat(procesos.get(id(1)).getStatus()).isEqualTo(CONFIRMED);
        assertThat(procesos.writes()).isEmpty();
        assertThat(salida.getAll()).doesNotContain(MOTIVO);
    }

    @Test
    void cu10_7a_siAppointmentCancelledLlegoAntesDeGuardarSeRespondeElEstadoActual() {
        procesos.put(ReservationProcesses.in(CONFIRMED, ANA, LocalTime.of(10, 0), 1));
        // El evento llega mientras se espera la respuesta de la cátedra, con su propio motivo e instante.
        catedra.mientrasTanto = () ->
            procesos.changeFromOutside(id(1), proceso -> {
                proceso.cancelled(new Cancellation(null, ANTES), ANTES);
                return proceso;
            });

        CancelReservationResult resultado = casoDeUso.cancel(new CancelReservationCommand(ANA, id(1), MOTIVO));

        assertThat(resultado).isInstanceOfSatisfying(CancelReservationResult.Cancelled.class, cancelado ->
            assertThat(cancelado.process().getCancellation()).contains(new Cancellation(null, ANTES))
        );
        assertThat(procesos.writes()).isEmpty();
    }

    @Test
    void cu10_7a_siElEventoGuardaEntreLaRelecturaYElGuardadoSeReintentaYQuedaElDelEvento() {
        procesos.put(ReservationProcesses.in(CONFIRMED, ANA, LocalTime.of(10, 0), 1));
        procesos.beforeNextSave(() ->
            procesos.changeFromOutside(id(1), proceso -> {
                proceso.cancelled(new Cancellation(null, ANTES), ANTES);
                return proceso;
            })
        );

        CancelReservationResult resultado = casoDeUso.cancel(new CancelReservationCommand(ANA, id(1), MOTIVO));

        assertThat(resultado).isInstanceOfSatisfying(CancelReservationResult.Cancelled.class, cancelado -> {
            assertThat(cancelado.process().getStatus()).isEqualTo(CANCELLED);
            assertThat(cancelado.process().getCancellation()).contains(new Cancellation(null, ANTES));
        });
    }

    @Test
    void siLaCancelacionSeAplicoPeroNoSePudoGuardarEsErrorInternoSinElMotivoEnElLog(CapturedOutput salida) {
        procesos.put(ReservationProcesses.in(CONFIRMED, ANA, LocalTime.of(10, 0), 1));
        procesos.failNextSaves(1, new DataAccessResourceFailureException("la base no responde"));

        assertThat(casoDeUso.cancel(new CancelReservationCommand(ANA, id(1), MOTIVO))).isEqualTo(new CancelReservationResult.InternalError());

        assertThat(procesos.get(id(1)).getStatus()).isEqualTo(CONFIRMED);
        assertThat(salida.getAll()).contains("queda CONFIRMED hasta que converja").doesNotContain(MOTIVO);
    }

    @Test
    void elComandoNoMuestraElMotivoYRechazaMasDe500Caracteres() {
        assertThat(new CancelReservationCommand(ANA, id(1), MOTIVO).toString()).doesNotContain(MOTIVO);
        assertThat(new Cancellation(MOTIVO, CANCELADA_EN).toString()).doesNotContain(MOTIVO);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new CancelReservationCommand(ANA, id(1), "x".repeat(501))).isInstanceOf(
            IllegalArgumentException.class
        );
    }
}
