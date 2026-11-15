package ar.edu.um.programacion2.turnos.reservation.application.usecases;

import static ar.edu.um.programacion2.turnos.reservation.application.usecases.ReservationProcesses.holdId;
import static ar.edu.um.programacion2.turnos.reservation.application.usecases.ReservationProcesses.id;
import static ar.edu.um.programacion2.turnos.reservation.application.usecases.ReservationProcesses.reservationProcessId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.um.programacion2.turnos.reservation.domain.model.EventOutcome;
import ar.edu.um.programacion2.turnos.reservation.domain.model.NoEffectReason;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PhoneRequest;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationEvent;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;

/** CU-05 con la persistencia en memoria y un reloj fijo: un test por flujo, con su número. */
@ExtendWith(OutputCaptureExtension.class)
class HandlePhoneRequestedUseCaseImplTest {

    private static final Instant AHORA = Instant.parse("2026-12-14T12:30:00Z");
    private static final Instant VENCE_NUEVO = Instant.parse("2026-12-14T12:44:50Z");

    private final FakeTransactions transacciones = new FakeTransactions();
    private final InMemoryReservationProcessRepository procesos = new InMemoryReservationProcessRepository(transacciones);
    private final InMemoryProcessedEventRepository eventos = new InMemoryProcessedEventRepository(transacciones);
    private final HandlePhoneRequestedUseCaseImpl casoDeUso = new HandlePhoneRequestedUseCaseImpl(
        new ReservationEventProcessor(eventos, procesos, new EventTransactions(transacciones), Clock.fixed(AHORA, ZoneOffset.UTC))
    );

    @Test
    void cu05_principal_guardaElEventIdElVencimientoYElMensajeYRegistraElEvento() {
        procesos.put(proceso(ReservationProcessStatus.WAITING_FOR_PHONE_REQUEST));

        EventOutcome resultado = casoDeUso.handle(pedido("request-event-8a31", holdId(1)));

        assertThat(resultado).isEqualTo(new EventOutcome.Applied(ReservationProcessStatus.WAITING_FOR_PHONE_REQUEST, ReservationProcessStatus.PHONE_REQUESTED));
        ReservationProcess guardado = procesos.get(id(1));
        assertThat(guardado.getStatus()).isEqualTo(ReservationProcessStatus.PHONE_REQUESTED);
        assertThat(guardado.getPhoneRequest()).contains(new PhoneRequest("request-event-8a31", "Ingrese un numero de telefono para continuar."));
        assertThat(guardado.getHold().orElseThrow().expiresAt()).isEqualTo(VENCE_NUEVO);
        assertThat(guardado.getStatusChangedAt()).isEqualTo(AHORA);
        assertThat(eventos.registered()).isEqualTo(Map.of("request-event-8a31", "AdditionalInformationRequested"));
        // Todo en una sola transacción: barrera, búsqueda, transición, guardado.
        assertThat(transacciones.opened()).isEqualTo(1);
    }

    @Test
    void cu05_3_1a_elMismoEventIdOtraVezNoRepiteEfectos() {
        procesos.put(proceso(ReservationProcessStatus.WAITING_FOR_PHONE_REQUEST));
        casoDeUso.handle(pedido("request-event-8a31", holdId(1)));
        long version = procesos.get(id(1)).getVersion();

        assertThat(casoDeUso.handle(pedido("request-event-8a31", holdId(1)))).isEqualTo(new EventOutcome.Duplicate());

        assertThat(procesos.get(id(1)).getVersion()).isEqualTo(version);
        assertThat(procesos.writes()).hasSize(1);
    }

    @Test
    void cu05_3_2a_sinProcesoLocalElEventoQuedaRegistradoSinEfectoYNoSeCreaNada() {
        assertThat(casoDeUso.handle(pedido("request-event-8a31", holdId(1)))).isEqualTo(new EventOutcome.ProcessNotFound());

        assertThat(procesos.all()).isEmpty();
        assertThat(eventos.registered()).containsKey("request-event-8a31");
    }

    @Test
    void cu05_3_3a_conElProcesoEnHoldCreatedPasaIgualAPhoneRequested() {
        procesos.put(proceso(ReservationProcessStatus.HOLD_CREATED));

        assertThat(casoDeUso.handle(pedido("request-event-8a31", holdId(1)))).isEqualTo(
            new EventOutcome.Applied(ReservationProcessStatus.HOLD_CREATED, ReservationProcessStatus.PHONE_REQUESTED)
        );
    }

    @ParameterizedTest
    @EnumSource(value = ReservationProcessStatus.class, names = { "CONFIRMED", "CANCELLED", "EXPIRED", "INVALID", "FAILED" })
    void cu05_3_3b_unEventoTardioNoReabreUnProcesoYQuedaRegistrado(ReservationProcessStatus estado) {
        procesos.put(proceso(estado));

        EventOutcome resultado = casoDeUso.handle(pedido("request-event-tardio", holdId(1)));

        NoEffectReason motivo = estado == ReservationProcessStatus.CONFIRMED ? NoEffectReason.CONFIRMED_ONLY_CANCELS : NoEffectReason.FINAL_STATE;
        assertThat(resultado).isEqualTo(new EventOutcome.NoEffect(estado, motivo));
        assertThat(procesos.get(id(1)).getStatus()).isEqualTo(estado);
        assertThat(procesos.writes()).isEmpty();
        assertThat(eventos.registered()).containsKey("request-event-tardio");
    }

    @ParameterizedTest
    @EnumSource(value = ReservationProcessStatus.class, names = { "PHONE_REQUESTED", "PHONE_SUBMITTED", "PHONE_REJECTED" })
    void cu05_3_3c_otroPedidoConOtroEventIdNoCambiaNadaYQuedaRegistrado(ReservationProcessStatus estado) {
        procesos.put(proceso(estado));

        assertThat(casoDeUso.handle(pedido("request-event-otro", holdId(1)))).isEqualTo(new EventOutcome.NoEffect(estado, NoEffectReason.NOT_FORWARD));

        // El requestEventId guardado sigue siendo el del primer pedido.
        assertThat(procesos.get(id(1)).getPhoneRequest().orElseThrow().requestEventId()).isEqualTo("request-1");
        assertThat(eventos.registered()).containsKey("request-event-otro");
    }

    @Test
    void cu05_unHoldIdQueNoCoincideSeAplicaIgualPorReservationProcessIdYDejaUnaAdvertencia(CapturedOutput salida) {
        procesos.put(proceso(ReservationProcessStatus.WAITING_FOR_PHONE_REQUEST));

        EventOutcome resultado = casoDeUso.handle(pedido("request-event-8a31", "hold-de-otro"));

        assertThat(resultado).isInstanceOf(EventOutcome.Applied.class);
        assertThat(salida.getAll()).contains("el holdId no coincide con el guardado");
    }

    @Test
    void cu05_3a_siFallaElGuardadoLaExcepcionSaleParaQueElListenerReintente() {
        procesos.put(proceso(ReservationProcessStatus.WAITING_FOR_PHONE_REQUEST));
        procesos.failNextSaves(1, new DataAccessResourceFailureException("la base no responde"));

        assertThatThrownBy(() -> casoDeUso.handle(pedido("request-event-8a31", holdId(1)))).isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void cu05_siUnPedidoHttpCambioElProcesoEnElMedioElChoqueDeVersionSaleParaQueElListenerReintente() {
        procesos.put(proceso(ReservationProcessStatus.HOLD_CREATED));
        procesos.beforeNextSave(() -> procesos.changeFromOutside(id(1), process -> process));

        assertThatThrownBy(() -> casoDeUso.handle(pedido("request-event-8a31", holdId(1)))).isInstanceOf(OptimisticLockingFailureException.class);
    }

    private static ReservationProcess proceso(ReservationProcessStatus estado) {
        return ReservationProcesses.in(estado, 42, LocalTime.parse("10:00"), 1);
    }

    private static ReservationEvent.PhoneRequested pedido(String eventId, String holdId) {
        return new ReservationEvent.PhoneRequested(eventId, reservationProcessId(1), holdId, "Ingrese un numero de telefono para continuar.", VENCE_NUEVO);
    }
}
