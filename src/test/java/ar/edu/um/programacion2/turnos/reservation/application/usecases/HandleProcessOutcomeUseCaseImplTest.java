package ar.edu.um.programacion2.turnos.reservation.application.usecases;

import static ar.edu.um.programacion2.turnos.reservation.application.usecases.ReservationProcesses.VENCE;
import static ar.edu.um.programacion2.turnos.reservation.application.usecases.ReservationProcesses.id;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.CANCELLED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.CONFIRMED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.EXPIRED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.INVALID;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.PHONE_REJECTED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.PHONE_SUBMITTED;
import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.turnos.reservation.domain.model.Cancellation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.Closing;
import ar.edu.um.programacion2.turnos.reservation.domain.model.Confirmation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.EventOutcome;
import ar.edu.um.programacion2.turnos.reservation.domain.model.NoEffectReason;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PhoneRejection;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationEvent;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationEvent.AppointmentCancelled;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationEvent.AppointmentConfirmed;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationEvent.PhoneRejected;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationEvent.ProcessExpired;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationEvent.ProcessInvalid;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * CU-07 con la persistencia en memoria y un reloj fijo. Un test por fila de la tabla de eventos y por flujo alternativo.
 * Los "sin efecto" se registran siempre (la barrera lo hace antes de la transición).
 */
@ExtendWith(OutputCaptureExtension.class)
class HandleProcessOutcomeUseCaseImplTest {

    private static final Instant AHORA = Instant.parse("2026-12-14T12:30:00Z");
    private static final Instant OCURRIO = Instant.parse("2026-12-14T12:29:58Z");
    private static final Instant VENCE_NUEVO = Instant.parse("2026-12-14T12:44:50Z");

    private final FakeTransactions transacciones = new FakeTransactions();
    private final InMemoryReservationProcessRepository procesos = new InMemoryReservationProcessRepository(transacciones);
    private final InMemoryProcessedEventRepository eventos = new InMemoryProcessedEventRepository(transacciones);
    private final HandleProcessOutcomeUseCaseImpl casoDeUso = new HandleProcessOutcomeUseCaseImpl(
        new ReservationEventProcessor(eventos, procesos, new EventTransactions(transacciones), Clock.fixed(AHORA, ZoneOffset.UTC))
    );

    // ---------------------------------------------------------------------------------------------------------------
    // La tabla de eventos
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void cu07_confirmado_desdePhoneSubmittedGuardaElReservationIdYElOccurredAt() {
        procesos.put(proceso(PHONE_SUBMITTED));

        assertThat(casoDeUso.handle(confirmado("e-1", OCURRIO))).isEqualTo(new EventOutcome.Applied(PHONE_SUBMITTED, CONFIRMED));

        ReservationProcess guardado = procesos.get(id(1));
        assertThat(guardado.getConfirmation()).contains(new Confirmation(8251, OCURRIO));
        assertThat(guardado.getStatusChangedAt()).isEqualTo(AHORA);
        assertThat(eventos.registered()).containsEntry("e-1", "AppointmentConfirmed");
    }

    @Test
    void cu07_confirmado_sinOccurredAtUsaElInstanteDelReloj() {
        procesos.put(proceso(PHONE_SUBMITTED));

        casoDeUso.handle(confirmado("e-1", null));

        assertThat(procesos.get(id(1)).getConfirmation()).contains(new Confirmation(8251, AHORA));
    }

    @ParameterizedTest
    @EnumSource(value = ReservationProcessStatus.class, names = { "HOLD_CREATED", "WAITING_FOR_PHONE_REQUEST", "PHONE_REQUESTED", "PHONE_REJECTED" })
    void cu07_2_3d_unaConfirmacionQueSeAdelantaSeAcepta(ReservationProcessStatus estado) {
        procesos.put(proceso(estado));

        assertThat(casoDeUso.handle(confirmado("e-1", OCURRIO))).isEqualTo(new EventOutcome.Applied(estado, CONFIRMED));
    }

    @Test
    void cu07_rechazo_desdePhoneSubmittedGuardaElMotivoElMensajeYElVencimientoNuevo() {
        procesos.put(proceso(PHONE_SUBMITTED));

        assertThat(casoDeUso.handle(rechazo("e-1", VENCE_NUEVO))).isEqualTo(new EventOutcome.Applied(PHONE_SUBMITTED, PHONE_REJECTED));

        ReservationProcess guardado = procesos.get(id(1));
        assertThat(guardado.getRejection()).contains(new PhoneRejection("INVALID_PHONE_NUMBER", "El telefono ingresado no es valido."));
        assertThat(guardado.getHold().orElseThrow().expiresAt()).isEqualTo(VENCE_NUEVO);
    }

    @Test
    void cu07_rechazo_sinExpiresAtConservaElVencimientoGuardado() {
        procesos.put(proceso(PHONE_SUBMITTED));

        casoDeUso.handle(rechazo("e-1", null));

        assertThat(procesos.get(id(1)).getHold().orElseThrow().expiresAt()).isEqualTo(VENCE);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationProcessStatus.class, names = { "HOLD_CREATED", "WAITING_FOR_PHONE_REQUEST", "PHONE_REQUESTED", "PHONE_SUBMITTED", "PHONE_REJECTED" })
    void cu07_vencidoEInvalido_desdeCualquierIntermedioGuardanElReasonTalCual(ReservationProcessStatus estado) {
        procesos.put(proceso(estado));
        assertThat(casoDeUso.handle(new ProcessExpired("e-1", "process-1", "hold-1", null, "HOLD_EXPIRED"))).isEqualTo(new EventOutcome.Applied(estado, EXPIRED));
        assertThat(procesos.get(id(1)).getClosing()).contains(new Closing("HOLD_EXPIRED", null));

        procesos.put(ReservationProcesses.in(estado, 42, LocalTime.parse("11:00"), 2));
        assertThat(casoDeUso.handle(new ProcessInvalid("e-2", "process-2", "hold-2", null, "REQUEST_EVENT_MISMATCH"))).isEqualTo(new EventOutcome.Applied(estado, INVALID));
        assertThat(procesos.get(id(2)).getClosing()).contains(new Closing("REQUEST_EVENT_MISMATCH", null));
    }

    @Test
    void cu07_cancelado_desdeConfirmedGuardaElReasonYElOccurredAt() {
        procesos.put(proceso(CONFIRMED));

        assertThat(casoDeUso.handle(cancelado("e-1", "Cambio solicitado por el usuario", OCURRIO))).isEqualTo(new EventOutcome.Applied(CONFIRMED, CANCELLED));

        assertThat(procesos.get(id(1)).getCancellation()).contains(new Cancellation("Cambio solicitado por el usuario", OCURRIO));
    }

    @Test
    void cu07_cancelado_conReasonNullYSinOccurredAt() {
        procesos.put(proceso(CONFIRMED));

        casoDeUso.handle(cancelado("e-1", null, null));

        assertThat(procesos.get(id(1)).getCancellation()).contains(new Cancellation(null, AHORA));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Flujos alternativos
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void cu07_2_1a_unResultadoDuplicadoNoCambiaNada() {
        procesos.put(proceso(PHONE_SUBMITTED));
        casoDeUso.handle(confirmado("e-1", OCURRIO));
        long version = procesos.get(id(1)).getVersion();

        assertThat(casoDeUso.handle(confirmado("e-1", OCURRIO))).isEqualTo(new EventOutcome.Duplicate());

        assertThat(procesos.get(id(1)).getVersion()).isEqualTo(version);
    }

    @Test
    void cu07_2_2a_unResultadoDeUnProcesoQueNoExisteQuedaRegistradoSinCrearNada() {
        assertThat(casoDeUso.handle(confirmado("e-1", OCURRIO))).isEqualTo(new EventOutcome.ProcessNotFound());

        assertThat(procesos.all()).isEmpty();
        assertThat(eventos.registered()).containsKey("e-1");
    }

    static Stream<Arguments> tardiosSobreUnFinal() {
        return Stream.of(CANCELLED, EXPIRED, INVALID, ReservationProcessStatus.FAILED).flatMap(estado ->
            resultados().map(evento -> Arguments.of(estado, evento))
        );
    }

    /** 2.3a: los cinco resultados sobre los cuatro finales. Ninguno reabre nada, y todos quedan registrados. */
    @ParameterizedTest(name = "{1} sobre {0}")
    @MethodSource("tardiosSobreUnFinal")
    void cu07_2_3a_unResultadoTardioNoModificaUnEstadoFinal(ReservationProcessStatus estado, ReservationEvent.OutcomeEvent evento) {
        procesos.put(proceso(estado));
        String antes = procesos.get(id(1)).toString() + procesos.get(id(1)).getVersion();

        assertThat(casoDeUso.handle(evento)).isEqualTo(new EventOutcome.NoEffect(estado, NoEffectReason.FINAL_STATE));

        assertThat(procesos.get(id(1)).toString() + procesos.get(id(1)).getVersion()).isEqualTo(antes);
        assertThat(procesos.writes()).isEmpty();
        assertThat(eventos.registered()).containsKey(evento.eventId());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("resultadosQueNoSonCancelacion")
    void cu07_2_3b_sobreConfirmedSoloLaCancelacionTieneEfecto(ReservationEvent.OutcomeEvent evento) {
        procesos.put(proceso(CONFIRMED));

        assertThat(casoDeUso.handle(evento)).isEqualTo(new EventOutcome.NoEffect(CONFIRMED, NoEffectReason.CONFIRMED_ONLY_CANCELS));

        assertThat(procesos.get(id(1)).getStatus()).isEqualTo(CONFIRMED);
        assertThat(eventos.registered()).containsKey(evento.eventId());
    }

    @ParameterizedTest
    @EnumSource(value = ReservationProcessStatus.class, names = { "HOLD_CREATED", "WAITING_FOR_PHONE_REQUEST", "PHONE_REQUESTED" })
    void cu07_2_3c_unRechazoFueraDePhoneSubmittedNoTieneEfecto(ReservationProcessStatus estado) {
        procesos.put(proceso(estado));

        assertThat(casoDeUso.handle(rechazo("e-1", VENCE_NUEVO))).isEqualTo(new EventOutcome.NoEffect(estado, NoEffectReason.NOT_APPLICABLE));

        assertThat(procesos.get(id(1)).getHold().orElseThrow().expiresAt()).isEqualTo(VENCE);
    }

    @Test
    void cu07_2_3c_unSegundoRechazoConOtroEventIdNoTieneEfecto() {
        procesos.put(proceso(PHONE_REJECTED));

        assertThat(casoDeUso.handle(rechazo("e-otro", VENCE_NUEVO))).isEqualTo(new EventOutcome.NoEffect(PHONE_REJECTED, NoEffectReason.NOT_FORWARD));
    }

    @ParameterizedTest
    @EnumSource(value = ReservationProcessStatus.class, names = { "HOLD_CREATED", "WAITING_FOR_PHONE_REQUEST", "PHONE_REQUESTED", "PHONE_SUBMITTED", "PHONE_REJECTED" })
    void cu07_2_3e_unaCancelacionSobreUnIntermedioNoTieneEfecto(ReservationProcessStatus estado) {
        procesos.put(proceso(estado));

        assertThat(casoDeUso.handle(cancelado("e-1", null, OCURRIO))).isEqualTo(new EventOutcome.NoEffect(estado, NoEffectReason.NOT_APPLICABLE));

        assertThat(procesos.get(id(1)).getStatus()).isEqualTo(estado);
    }

    @Test
    void cu07_unRequestEventIdQueNoCoincideSeAplicaIgualConUnaAdvertencia(CapturedOutput salida) {
        procesos.put(proceso(PHONE_SUBMITTED));

        assertThat(casoDeUso.handle(new AppointmentConfirmed("e-1", "process-1", "hold-1", "otro-pedido", 8251, OCURRIO))).isInstanceOf(EventOutcome.Applied.class);

        assertThat(salida.getAll()).contains("el requestEventId no coincide con el guardado");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Ayudas
    // ---------------------------------------------------------------------------------------------------------------

    static Stream<ReservationEvent.OutcomeEvent> resultados() {
        return Stream.concat(resultadosQueNoSonCancelacion(), Stream.of(cancelado("e-cancelado", null, OCURRIO)));
    }

    static Stream<ReservationEvent.OutcomeEvent> resultadosQueNoSonCancelacion() {
        return Stream.of(
            confirmado("e-confirmado", OCURRIO),
            rechazo("e-rechazo", VENCE_NUEVO),
            new ProcessExpired("e-vencido", "process-1", "hold-1", "request-1", "HOLD_EXPIRED"),
            new ProcessInvalid("e-invalido", "process-1", "hold-1", "request-1", "REQUEST_EVENT_MISMATCH")
        );
    }

    private static ReservationProcess proceso(ReservationProcessStatus estado) {
        return ReservationProcesses.in(estado, 42, LocalTime.parse("10:00"), 1);
    }

    private static AppointmentConfirmed confirmado(String eventId, Instant ocurrio) {
        return new AppointmentConfirmed(eventId, "process-1", "hold-1", "request-1", 8251, ocurrio);
    }

    private static PhoneRejected rechazo(String eventId, Instant vence) {
        return new PhoneRejected(eventId, "process-1", "hold-1", "request-1", "INVALID_PHONE_NUMBER", "El telefono ingresado no es valido.", vence);
    }

    private static AppointmentCancelled cancelado(String eventId, String motivo, Instant ocurrio) {
        return new AppointmentCancelled(eventId, "process-1", "hold-1", "request-1", motivo, ocurrio);
    }
}
