package ar.edu.um.programacion2.turnos.reservation.application.usecases;

import static ar.edu.um.programacion2.turnos.reservation.application.usecases.ReservationProcesses.VENCE;
import static ar.edu.um.programacion2.turnos.reservation.application.usecases.ReservationProcesses.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ar.edu.um.programacion2.turnos.reservation.domain.model.Confirmation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PhoneNumber;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PhoneRejection;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PhoneSubmission;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.programacion2.turnos.reservation.domain.model.SubmitPhoneCommand;
import ar.edu.um.programacion2.turnos.reservation.domain.model.SubmitPhoneResult;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.PhoneSubmissionNotSentException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.PhoneSubmissionPublisher;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.PhoneSubmissionUnconfirmedException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * CU-06 con la persistencia en memoria, el productor simulado y un reloj fijo: un test por flujo, con su número. "Ahora"
 * es 12:30 UTC y el hold vence a las 12:40. Después de cada test se comprueba que la publicación no se hizo adentro de una
 * transacción.
 */
@ExtendWith(OutputCaptureExtension.class)
class SubmitPhoneUseCaseImplTest {

    private static final long ANA = 42;
    private static final Instant AHORA = Instant.parse("2026-12-14T12:30:00Z");
    private static final String TELEFONO = "+54 9 (261) 555-12.34";

    private final FakeTransactions transacciones = new FakeTransactions();
    private final InMemoryReservationProcessRepository procesos = new InMemoryReservationProcessRepository(transacciones);
    private final PhoneSubmissionPublisher productor = mock(PhoneSubmissionPublisher.class);
    private final List<String> publicacionesAdentroDeUnaTransaccion = new ArrayList<>();
    private Clock reloj = Clock.fixed(AHORA, ZoneOffset.UTC);

    @BeforeEach
    void elProductorConfirma() {
        doAnswer(invocacion -> {
            if (transacciones.isActive()) {
                publicacionesAdentroDeUnaTransaccion.add("publish");
            }
            return null;
        })
            .when(productor)
            .publish(any());
    }

    @AfterEach
    void laPublicacionNuncaVaAdentroDeUnaTransaccion() {
        assertThat(publicacionesAdentroDeUnaTransaccion).isEmpty();
    }

    @Test
    void cu06_principal_publicaElEventoDelProcesoYGuardaPhoneSubmittedConElEventId() {
        procesos.put(proceso(ReservationProcessStatus.PHONE_REQUESTED));

        SubmitPhoneResult resultado = casoDeUso().submit(pedido(TELEFONO));

        ArgumentCaptor<PhoneSubmission> enviado = ArgumentCaptor.forClass(PhoneSubmission.class);
        verify(productor).publish(enviado.capture());
        assertThat(enviado.getValue()).isEqualTo(
            new PhoneSubmission("evento-nuevo-1", "process-1", "hold-1", "request-1", PhoneNumber.of("+5492615551234"), AHORA)
        );
        assertThat(resultado).isInstanceOf(SubmitPhoneResult.Submitted.class);
        ReservationProcess guardado = procesos.get(id(1));
        assertThat(guardado.getStatus()).isEqualTo(ReservationProcessStatus.PHONE_SUBMITTED);
        assertThat(guardado.getPhone()).contains(PhoneNumber.of("+5492615551234"));
        assertThat(guardado.getLastSubmittedEventId()).contains("evento-nuevo-1");
        assertThat(((SubmitPhoneResult.Submitted) resultado).process().getStatus()).isEqualTo(ReservationProcessStatus.PHONE_SUBMITTED);
    }

    @Test
    void cu06_despuesDeUnRechazoSeReenviaConUnEventIdNuevoYElMismoRequestEventId() {
        procesos.put(proceso(ReservationProcessStatus.PHONE_REJECTED));

        assertThat(casoDeUso().submit(pedido("2615559999"))).isInstanceOf(SubmitPhoneResult.Submitted.class);

        ArgumentCaptor<PhoneSubmission> enviado = ArgumentCaptor.forClass(PhoneSubmission.class);
        verify(productor).publish(enviado.capture());
        assertThat(enviado.getValue().eventId()).isEqualTo("evento-nuevo-1").isNotEqualTo("submitted-1");
        assertThat(enviado.getValue().requestEventId()).isEqualTo("request-1");
        assertThat(procesos.get(id(1)).getStatus()).isEqualTo(ReservationProcessStatus.PHONE_SUBMITTED);
    }

    @Test
    void cu06_2b_unProcesoDeOtroUsuarioEsLoMismoQueUnoQueNoExiste() {
        procesos.put(ReservationProcesses.in(ReservationProcessStatus.PHONE_REQUESTED, 77, LocalTime.parse("10:00"), 1));

        assertThat(casoDeUso().submit(pedido(TELEFONO))).isEqualTo(new SubmitPhoneResult.NotFound());
        assertThat(casoDeUso().submit(new SubmitPhoneCommand(ANA, id(99), TELEFONO))).isEqualTo(new SubmitPhoneResult.NotFound());

        verifyNoInteractions(productor);
        assertThat(procesos.get(id(1)).getStatus()).isEqualTo(ReservationProcessStatus.PHONE_REQUESTED);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationProcessStatus.class, names = { "CREATING_HOLD", "HOLD_CREATED", "WAITING_FOR_PHONE_REQUEST" })
    void cu06_3a_sinPedidoDeTelefonoNoSeEnviaNada(ReservationProcessStatus estado) {
        procesos.put(proceso(estado));

        assertThat(casoDeUso().submit(pedido(TELEFONO))).isEqualTo(new SubmitPhoneResult.NotRequestedYet());

        verifyNoInteractions(productor);
        assertThat(procesos.writes()).isEmpty();
    }

    @Test
    void cu06_3b_conElTelefonoYaEnviadoNoSeEnviaOtro() {
        procesos.put(proceso(ReservationProcessStatus.PHONE_SUBMITTED));

        assertThat(casoDeUso().submit(pedido(TELEFONO))).isEqualTo(new SubmitPhoneResult.AlreadySubmitted());

        verifyNoInteractions(productor);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationProcessStatus.class, names = { "CONFIRMED", "CANCELLED", "EXPIRED", "INVALID", "FAILED" })
    void cu06_3c_conElProcesoConfirmadoOCerradoSeRespondeSuEstado(ReservationProcessStatus estado) {
        procesos.put(proceso(estado));

        assertThat(casoDeUso().submit(pedido(TELEFONO))).isEqualTo(new SubmitPhoneResult.Closed(estado));

        verifyNoInteractions(productor);
    }

    @Test
    void cu06_3d_vencidoNoSeEnviaYElProcesoNoSeModifica() {
        procesos.put(proceso(ReservationProcessStatus.PHONE_REQUESTED));
        reloj = Clock.fixed(VENCE, ZoneOffset.UTC);

        assertThat(casoDeUso().submit(pedido(TELEFONO))).isEqualTo(new SubmitPhoneResult.DeadlinePassed());

        verifyNoInteractions(productor);
        assertThat(procesos.writes()).isEmpty();
        assertThat(procesos.get(id(1)).getStatus()).isEqualTo(ReservationProcessStatus.PHONE_REQUESTED);
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "123456", "+54 9 261 555 1234 5678", "261-ABC-1234", "++5492615551234" })
    void cu06_4a_unTelefonoInvalidoNoSeEnviaYElProcesoNoCambia(String telefono) {
        procesos.put(proceso(ReservationProcessStatus.PHONE_REQUESTED));

        assertThat(casoDeUso().submit(pedido(telefono))).isEqualTo(new SubmitPhoneResult.InvalidPhone());

        verifyNoInteractions(productor);
        assertThat(procesos.writes()).isEmpty();
    }

    @Test
    void cu06_6a_siKafkaNoConfirmaElProcesoNoCambiaYNoSeGuardaNada(CapturedOutput salida) {
        procesos.put(proceso(ReservationProcessStatus.PHONE_REQUESTED));
        doThrow(new PhoneSubmissionUnconfirmedException("sin ack", null)).when(productor).publish(any());

        assertThat(casoDeUso().submit(pedido(TELEFONO))).isEqualTo(new SubmitPhoneResult.Unconfirmed());

        assertThat(procesos.writes()).isEmpty();
        assertThat(procesos.get(id(1)).getLastSubmittedEventId()).isEmpty();
        assertThat(salida.getAll()).contains("pudo haber llegado").doesNotContain("5551234");
    }

    @Test
    void cu06_6a_siNoSePudoComprobarElTopicTampocoCambiaNada() {
        procesos.put(proceso(ReservationProcessStatus.PHONE_REQUESTED));
        doThrow(new PhoneSubmissionNotSentException("broker caído", null)).when(productor).publish(any());

        assertThat(casoDeUso().submit(pedido(TELEFONO))).isEqualTo(new SubmitPhoneResult.Unconfirmed());

        assertThat(procesos.writes()).isEmpty();
    }

    @Test
    void cu06_6a_cadaReenvioLlevaUnEventIdNuevo() {
        procesos.put(proceso(ReservationProcessStatus.PHONE_REQUESTED));
        doThrow(new PhoneSubmissionUnconfirmedException("sin ack", null)).doNothing().when(productor).publish(any());
        SubmitPhoneUseCaseImpl casoDeUso = casoDeUso();

        casoDeUso.submit(pedido(TELEFONO));
        casoDeUso.submit(pedido(TELEFONO));

        ArgumentCaptor<PhoneSubmission> enviados = ArgumentCaptor.forClass(PhoneSubmission.class);
        verify(productor, org.mockito.Mockito.times(2)).publish(enviados.capture());
        assertThat(enviados.getAllValues()).extracting(PhoneSubmission::eventId).containsExactly("evento-nuevo-1", "evento-nuevo-2");
        assertThat(procesos.get(id(1)).getLastSubmittedEventId()).contains("evento-nuevo-2");
    }

    /** 7a: AppointmentConfirmed llega entre la publicación y el guardado. No se retrocede, pero el eventId queda guardado. */
    @Test
    void cu06_7a_siElResultadoLlegoAntesDeGuardarNoSeRetrocedeYSeGuardaElEventId() {
        procesos.put(proceso(ReservationProcessStatus.PHONE_REQUESTED));
        doAnswer(invocacion -> {
            procesos.changeFromOutside(id(1), proceso -> {
                proceso.confirmed(new Confirmation(8251, AHORA), AHORA);
                return proceso;
            });
            return null;
        })
            .when(productor)
            .publish(any());

        SubmitPhoneResult resultado = casoDeUso().submit(pedido(TELEFONO));

        assertThat(((SubmitPhoneResult.Submitted) resultado).process().getStatus()).isEqualTo(ReservationProcessStatus.CONFIRMED);
        ReservationProcess guardado = procesos.get(id(1));
        assertThat(guardado.getStatus()).isEqualTo(ReservationProcessStatus.CONFIRMED);
        assertThat(guardado.getLastSubmittedEventId()).contains("evento-nuevo-1");
        assertThat(guardado.getPhone()).isEmpty();
    }

    /** 7a con un choque de versión: el evento entra entre la lectura y el UPDATE de este guardado. */
    @Test
    void cu06_7a_conUnChoqueDeVersionSeReleeYSeAplicaSobreElEstadoNuevo() {
        procesos.put(proceso(ReservationProcessStatus.PHONE_REQUESTED));
        procesos.beforeNextSave(() ->
            procesos.changeFromOutside(id(1), proceso -> {
                proceso.confirmed(new Confirmation(8251, AHORA), AHORA);
                return proceso;
            })
        );

        SubmitPhoneResult resultado = casoDeUso().submit(pedido(TELEFONO));

        assertThat(((SubmitPhoneResult.Submitted) resultado).process().getStatus()).isEqualTo(ReservationProcessStatus.CONFIRMED);
        assertThat(procesos.get(id(1)).getLastSubmittedEventId()).contains("evento-nuevo-1");
    }

    @Test
    void cu06_7a_unRechazoQueLlegoAntesDeGuardarDejaElProcesoListoParaReenviar() {
        procesos.put(proceso(ReservationProcessStatus.PHONE_REQUESTED));
        doAnswer(invocacion -> {
            procesos.changeFromOutside(id(1), proceso -> {
                proceso.phoneSubmitted(PhoneNumber.of("2615550000"), "otro-envio", AHORA);
                proceso.phoneRejected(new PhoneRejection("INVALID_PHONE_NUMBER", null), VENCE, AHORA);
                return proceso;
            });
            return null;
        })
            .when(productor)
            .publish(any());

        SubmitPhoneResult resultado = casoDeUso().submit(pedido(TELEFONO));

        // Desde PHONE_REJECTED el envío avanza: el teléfono de este pedido es el último enviado.
        assertThat(((SubmitPhoneResult.Submitted) resultado).process().getStatus()).isEqualTo(ReservationProcessStatus.PHONE_SUBMITTED);
    }

    @Test
    void cu06_7b_siElGuardadoFallaSeRespondeElErrorYQuedaUnErrorSinElTelefono(CapturedOutput salida) {
        procesos.put(proceso(ReservationProcessStatus.PHONE_REQUESTED));
        procesos.failNextSaves(1, new DataAccessResourceFailureException("la base no responde"));

        assertThat(casoDeUso().submit(pedido(TELEFONO))).isEqualTo(new SubmitPhoneResult.SaveFailed());

        verify(productor).publish(any());
        assertThat(procesos.get(id(1)).getStatus()).isEqualTo(ReservationProcessStatus.PHONE_REQUESTED);
        assertThat(salida.getAll()).contains("el teléfono se publicó pero no se pudo guardar", "eventId=evento-nuevo-1").doesNotContain("5551234");
    }

    @Test
    void elPedidoNoMuestraElTelefonoEnSuToString() {
        assertThat(pedido(TELEFONO).toString()).doesNotContain("555").doesNotContain("261");
    }

    private SubmitPhoneUseCaseImpl casoDeUso() {
        int[] siguiente = { 0 };
        return new SubmitPhoneUseCaseImpl(
            procesos,
            productor,
            new ReservationProcessUpdater(procesos, transacciones),
            () -> "evento-nuevo-" + ++siguiente[0],
            reloj,
            transacciones
        );
    }

    private static ReservationProcess proceso(ReservationProcessStatus estado) {
        return ReservationProcesses.in(estado, ANA, LocalTime.parse("10:00"), 1);
    }

    private static SubmitPhoneCommand pedido(String telefono) {
        return new SubmitPhoneCommand(ANA, id(1), telefono);
    }
}
