package ar.edu.um.programacion2.turnos.reservation.domain.model;

import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessMatrix.CAMBIADO;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessMatrix.VENCE;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.CANCELLED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.CONFIRMED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.CREATING_HOLD;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.EXPIRED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.FAILED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.HOLD_CREATED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.INVALID;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.PHONE_REJECTED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.PHONE_REQUESTED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.PHONE_SUBMITTED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.WAITING_FOR_PHONE_REQUEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.um.programacion2.turnos.reservation.domain.model.ReconciliationAction.Apply;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReconciliationAction.RetryConfirm;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReconciliationAction.Wait;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReconciliationAction.WaitReason;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * La tabla de CU-11 entera, con un reloj fijo y los bordes exactos. Los procesos son los de la matriz: el último cambio de
 * estado es {@link ReservationProcessMatrix#CAMBIADO} (11:30) y el hold vence en {@link ReservationProcessMatrix#VENCE}
 * (12:10). Con la configuración por defecto: antigüedad mínima 1 min, margen 1 min y espera máxima de
 * {@code PHONE_PENDING} 10 min.
 */
class ReconciliationPolicyTest {

    private static final ReconciliationPolicy POLITICA = new ReconciliationPolicy(Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(10));
    private static final Duration UN_MS = Duration.ofMillis(1);
    private static final List<ReservationProcessStatus> ESPERANDO_EL_TELEFONO = List.of(
        WAITING_FOR_PHONE_REQUEST,
        PHONE_REQUESTED,
        PHONE_SUBMITTED,
        PHONE_REJECTED
    );

    /** Qué se espera: un estado nuevo con su motivo de cierre, reintentar el confirm, o esperar con un motivo. */
    sealed interface Esperado {
        record Pasa(ReservationProcessStatus estado, String motivo) implements Esperado {}

        record Reintenta() implements Esperado {}

        record Espera(WaitReason motivo) implements Esperado {}
    }

    /** Un caso: el estado local, el instante, lo que informa la cátedra (o {@code null} si no está) y si la lista está entera. */
    record Caso(String nombre, ReservationProcessStatus local, Instant ahora, CatedraReservationStatus catedra, boolean listaEntera, Esperado esperado) {
        @Override
        public String toString() {
            return nombre + " (" + local + ")";
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Primer paso: sin la cátedra
    // ---------------------------------------------------------------------------------------------------------------

    static List<Caso> sinCatedra() {
        List<Caso> casos = new ArrayList<>();
        Instant justoMinAge = CAMBIADO.plus(Duration.ofMinutes(1));
        casos.add(new Caso("cu11_creatingHold_exactamente1MinSinCambiosTodaviaNo_DPT24", CREATING_HOLD, justoMinAge, null, true, new Esperado.Espera(WaitReason.NOT_DUE)));
        casos.add(
            new Caso(
                "cu11_creatingHold_masDe1MinPasaAFailedSinPreguntar",
                CREATING_HOLD,
                justoMinAge.plus(UN_MS),
                null,
                true,
                new Esperado.Pasa(FAILED, "HOLD_OUTCOME_UNKNOWN")
            )
        );
        casos.add(new Caso("cu11_holdCreated_exactamente1MinSinCambiosTodaviaNo_DPT24", HOLD_CREATED, justoMinAge, null, true, new Esperado.Espera(WaitReason.NOT_DUE)));
        for (ReservationProcessStatus estado : ESPERANDO_EL_TELEFONO) {
            casos.add(
                new Caso(
                    "cu11_esperando_exactamenteEnExpiresAtMas1MinTodaviaNo",
                    estado,
                    VENCE.plus(Duration.ofMinutes(1)),
                    null,
                    true,
                    new Esperado.Espera(WaitReason.NOT_DUE)
                )
            );
        }
        for (ReservationProcessStatus estado : List.of(CONFIRMED, CANCELLED, EXPIRED, INVALID, FAILED)) {
            casos.add(new Caso("cu11_2_soloIntermedios", estado, VENCE.plus(Duration.ofHours(1)), null, true, new Esperado.Espera(WaitReason.NOT_INTERMEDIATE)));
        }
        return casos;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sinCatedra")
    void elPrimerPasoDecideSinPreguntarleALaCatedra(Caso caso) {
        ReservationProcess proceso = ReservationProcessMatrix.procesoEn(caso.local(), 42);

        Optional<ReconciliationAction> accion = POLITICA.needsCatedra(proceso, caso.ahora());

        assertThat(accion).isPresent();
        comprobar(caso, proceso, accion.orElseThrow());
    }

    @Test
    void cu11_holdCreatedConMasDe1MinYLosQueEsperanPasadoElMargenLePreguntanALaCatedra() {
        assertThat(POLITICA.needsCatedra(ReservationProcessMatrix.procesoEn(HOLD_CREATED, 42), CAMBIADO.plus(Duration.ofMinutes(1)).plus(UN_MS))).isEmpty();
        for (ReservationProcessStatus estado : ESPERANDO_EL_TELEFONO) {
            assertThat(POLITICA.needsCatedra(ReservationProcessMatrix.procesoEn(estado, 42), VENCE.plus(Duration.ofMinutes(1)).plus(UN_MS))).as(estado.name()).isEmpty();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Segundo paso: con lo que informa la cátedra
    // ---------------------------------------------------------------------------------------------------------------

    static List<Caso> conCatedra() {
        Instant temprano = CAMBIADO.plus(Duration.ofMinutes(2));
        Instant pasadoElMargen = VENCE.plus(Duration.ofMinutes(1)).plus(UN_MS);
        List<Caso> casos = new ArrayList<>();

        // 3a: HOLD_CREATED con más de 1 minuto, antes de que venza.
        casos.add(new Caso("cu11_3a_phonePendingEsQueElConfirmSeAplico", HOLD_CREATED, temprano, CatedraReservationStatus.PHONE_PENDING, true, pasa(WAITING_FOR_PHONE_REQUEST, null)));
        casos.add(new Caso("cu11_3a_confirmadaEnLaCatedra", HOLD_CREATED, temprano, CatedraReservationStatus.CONFIRMED, true, pasa(CONFIRMED, null)));
        casos.add(new Caso("cu11_3a_canceladaEnLaCatedra", HOLD_CREATED, temprano, CatedraReservationStatus.CANCELLED, true, pasa(CANCELLED, null)));
        casos.add(new Caso("cu11_3a_ausenteYAntesDeExpiresAtSeReintentaElConfirm", HOLD_CREATED, temprano, null, true, new Esperado.Reintenta()));
        casos.add(
            new Caso("cu11_3a_ausenteJustoEnExpiresAtYaNoSeReintenta_DPT08", HOLD_CREATED, VENCE, null, true, new Esperado.Espera(WaitReason.EXPIRING))
        );
        casos.add(
            new Caso(
                "cu11_3a_ausenteHastaExpiresAtMas1MinSeEspera",
                HOLD_CREATED,
                VENCE.plus(Duration.ofMinutes(1)),
                null,
                true,
                new Esperado.Espera(WaitReason.EXPIRING)
            )
        );
        casos.add(new Caso("cu11_3a_ausenteDespuesDelMargenPasaAExpired", HOLD_CREATED, pasadoElMargen, null, true, pasa(EXPIRED, "NOT_FOUND_AFTER_EXPIRY")));
        casos.add(
            new Caso(
                "cu11_3a_ausenteDeUnaListaIncompletaNoPruebaNada",
                HOLD_CREATED,
                temprano,
                null,
                false,
                new Esperado.Espera(WaitReason.LIST_INCOMPLETE)
            )
        );
        casos.add(
            new Caso("cu11_3a_failedAntesDelMargenSeEspera", HOLD_CREATED, temprano, CatedraReservationStatus.FAILED, true, new Esperado.Espera(WaitReason.EXPIRING))
        );
        casos.add(new Caso("cu11_3a_failedDespuesDelMargenComo3b", HOLD_CREATED, pasadoElMargen, CatedraReservationStatus.FAILED, true, pasa(EXPIRED, "FAILED_IN_CATEDRA")));
        casos.add(
            new Caso(
                "cu11_3a_unEstadoDesconocidoNoSeToca",
                HOLD_CREATED,
                temprano,
                CatedraReservationStatus.UNRECOGNIZED,
                true,
                new Esperado.Espera(WaitReason.UNUSABLE_CATEDRA_DATA)
            )
        );

        // 3b: esperando el teléfono o el resultado, pasado expiresAt + 1 minuto.
        Instant justoEspera = VENCE.plus(Duration.ofMinutes(10));
        for (ReservationProcessStatus estado : ESPERANDO_EL_TELEFONO) {
            casos.add(new Caso("cu11_3b_confirmadaEnLaCatedra", estado, pasadoElMargen, CatedraReservationStatus.CONFIRMED, true, pasa(CONFIRMED, null)));
            casos.add(new Caso("cu11_3b_canceladaEnLaCatedra", estado, pasadoElMargen, CatedraReservationStatus.CANCELLED, true, pasa(CANCELLED, null)));
            casos.add(new Caso("cu11_3b_failedEnLaCatedra", estado, pasadoElMargen, CatedraReservationStatus.FAILED, true, pasa(EXPIRED, "FAILED_IN_CATEDRA")));
            casos.add(new Caso("cu11_3b_ausente", estado, pasadoElMargen, null, true, pasa(EXPIRED, "NOT_FOUND_AFTER_EXPIRY")));
            casos.add(
                new Caso("cu11_3b_ausenteDeUnaListaIncompletaNoPruebaNada", estado, pasadoElMargen, null, false, new Esperado.Espera(WaitReason.LIST_INCOMPLETE))
            );
            casos.add(
                new Caso(
                    "cu11_3b_phonePendingSeEspera",
                    estado,
                    pasadoElMargen,
                    CatedraReservationStatus.PHONE_PENDING,
                    true,
                    new Esperado.Espera(WaitReason.PHONE_PENDING)
                )
            );
            casos.add(
                new Caso(
                    "cu11_3b_phonePendingJustoEnExpiresAtMas10MinTodaviaSeEspera",
                    estado,
                    justoEspera,
                    CatedraReservationStatus.PHONE_PENDING,
                    true,
                    new Esperado.Espera(WaitReason.PHONE_PENDING)
                )
            );
            casos.add(
                new Caso(
                    "cu11_3b_phonePendingDespuesDeLaEsperaMaximaPasaAExpired",
                    estado,
                    justoEspera.plus(UN_MS),
                    CatedraReservationStatus.PHONE_PENDING,
                    true,
                    pasa(EXPIRED, "PHONE_PENDING_TIMEOUT")
                )
            );
            casos.add(
                new Caso(
                    "cu11_3b_unEstadoDesconocidoNoSeToca",
                    estado,
                    pasadoElMargen,
                    CatedraReservationStatus.UNRECOGNIZED,
                    true,
                    new Esperado.Espera(WaitReason.UNUSABLE_CATEDRA_DATA)
                )
            );
        }

        // 5a: el proceso releído ya no es intermedio (un evento lo cerró): no se hace nada, diga lo que diga la cátedra.
        for (ReservationProcessStatus estado : List.of(CONFIRMED, EXPIRED, CANCELLED)) {
            casos.add(
                new Caso(
                    "cu11_5a_unEventoLoCerroMientrasTanto",
                    estado,
                    pasadoElMargen,
                    CatedraReservationStatus.CANCELLED,
                    true,
                    new Esperado.Espera(WaitReason.NOT_INTERMEDIATE)
                )
            );
        }
        return casos;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conCatedra")
    void elSegundoPasoDecideConLoQueInformaLaCatedra(Caso caso) {
        ReservationProcess proceso = ReservationProcessMatrix.procesoEn(caso.local(), 42);
        Optional<CatedraReservation> enLaCatedra = Optional.ofNullable(caso.catedra()).map(ReconciliationPolicyTest::reserva);

        comprobar(caso, proceso, POLITICA.decide(proceso, enLaCatedra, caso.listaEntera(), caso.ahora()));
    }

    @Test
    void cu11_5a_unProcesoReleidoQueEmpezoAEsperarDeNuevoNoSeTocaTodavia() {
        // Un rechazo trajo un expiresAt nuevo mientras tanto: el proceso releído todavía no pasó el margen.
        ReservationProcess proceso = ReservationProcessMatrix.procesoEn(PHONE_REJECTED, 42);

        assertThat(POLITICA.decide(proceso, Optional.empty(), true, VENCE.minusSeconds(1))).isEqualTo(new Wait(WaitReason.NOT_DUE));
    }

    @Test
    void unaConfirmacionSinReservationIdNoSePuedeAplicarYSeEspera() {
        ReservationProcess proceso = ReservationProcessMatrix.procesoEn(PHONE_SUBMITTED, 42);
        CatedraReservation sinId = new CatedraReservation(
            "process-a92b47d0",
            "user-42",
            null,
            LocalDate.parse("2026-12-14"),
            LocalTime.parse("09:00"),
            CatedraReservationStatus.CONFIRMED,
            CAMBIADO,
            null,
            null
        );

        assertThat(POLITICA.decide(proceso, Optional.of(sinId), true, VENCE.plus(Duration.ofHours(1)))).isEqualTo(new Wait(WaitReason.UNUSABLE_CATEDRA_DATA));
    }

    @Test
    void laConfiguracionSeValida() {
        assertThatThrownBy(() -> new ReconciliationPolicy(Duration.ZERO, Duration.ofMinutes(1), Duration.ofMinutes(10))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReconciliationPolicy(Duration.ofMinutes(1), Duration.ofMinutes(-1), Duration.ofMinutes(10))).isInstanceOf(
            IllegalArgumentException.class
        );
        assertThatThrownBy(() -> new ReconciliationPolicy(Duration.ofMinutes(1), Duration.ofMinutes(10), Duration.ofMinutes(10))).isInstanceOf(
            IllegalArgumentException.class
        );
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static Esperado pasa(ReservationProcessStatus estado, String motivo) {
        return new Esperado.Pasa(estado, motivo);
    }

    /** Aplica la decisión al proceso (si es un hecho) y compara el estado y el motivo de cierre con lo esperado. */
    private static void comprobar(Caso caso, ReservationProcess proceso, ReconciliationAction accion) {
        switch (caso.esperado()) {
            case Esperado.Pasa(ReservationProcessStatus estado, String motivo) -> {
                assertThat(accion).isInstanceOf(Apply.class);
                TransitionResult resultado = ((Apply) accion).fact().applyTo(proceso, caso.ahora());
                assertThat(resultado).isEqualTo(new TransitionResult.Applied(caso.local(), estado));
                if (motivo != null) {
                    assertThat(proceso.getClosing()).contains(new Closing(motivo, null));
                    assertThat(((Apply) accion).reason()).isEqualTo(motivo);
                }
            }
            case Esperado.Reintenta() -> assertThat(accion).isEqualTo(new RetryConfirm());
            case Esperado.Espera(WaitReason motivo) -> assertThat(accion).isEqualTo(new Wait(motivo));
        }
    }

    private static CatedraReservation reserva(CatedraReservationStatus estado) {
        return new CatedraReservation(
            "process-a92b47d0",
            "user-42",
            8251L,
            LocalDate.parse("2026-12-14"),
            LocalTime.parse("09:00"),
            estado,
            estado == CatedraReservationStatus.CONFIRMED || estado == CatedraReservationStatus.CANCELLED ? CAMBIADO : null,
            estado == CatedraReservationStatus.CANCELLED ? CAMBIADO.plusSeconds(60) : null,
            null
        );
    }
}
