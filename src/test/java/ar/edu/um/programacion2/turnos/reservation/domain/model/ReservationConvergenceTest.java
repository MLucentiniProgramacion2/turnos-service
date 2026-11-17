package ar.edu.um.programacion2.turnos.reservation.domain.model;

import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessMatrix.AHORA;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessMatrix.CONFIRMACION;
import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.turnos.reservation.domain.model.ConvergenceFact.Degradation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ConvergenceFact.NothingReason;
import ar.edu.um.programacion2.turnos.reservation.domain.model.TransitionResult.Applied;
import ar.edu.um.programacion2.turnos.reservation.domain.model.TransitionResult.NoEffect;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * La convergencia de CU-09 (paso 6 y 6a), reusada por CU-11: la tabla entera, estado de la cátedra por estado local, y las
 * degradaciones de cada dato que puede faltar.
 */
class ReservationConvergenceTest {

    private static final Instant CONFIRMADA_EN = Instant.parse("2026-12-10T11:40:00Z");
    private static final Instant CANCELADA_EN = Instant.parse("2026-12-10T11:50:00Z");

    // Columnas: estado de la cátedra. Celdas: el estado local nuevo, o el motivo sin efecto (FS final, CO confirmado, NA
    // no corresponde, "-" nada que aplicar).
    // prettier-ignore
    private static final String TABLA = """
        LOCAL                     CONFIRMED  CANCELLED  PHONE_PENDING FAILED UNRECOGNIZED
        CREATING_HOLD             NA         NA         -             -      -
        HOLD_CREATED              CONFIRMED  CANCELLED  -             -      -
        WAITING_FOR_PHONE_REQUEST CONFIRMED  CANCELLED  -             -      -
        PHONE_REQUESTED           CONFIRMED  CANCELLED  -             -      -
        PHONE_SUBMITTED           CONFIRMED  CANCELLED  -             -      -
        PHONE_REJECTED            CONFIRMED  CANCELLED  -             -      -
        CONFIRMED                 CO         CANCELLED  -             -      -
        CANCELLED                 FS         FS         -             -      -
        EXPIRED                   FS         FS         -             -      -
        INVALID                   FS         FS         -             -      -
        FAILED                    FS         FS         -             -      -
        """;

    record Caso(ReservationProcessStatus local, CatedraReservationStatus catedra, String esperado) {
        @Override
        public String toString() {
            return "cu09_6: local " + local + " + cátedra " + catedra + " -> " + esperado;
        }
    }

    static List<Caso> casos() {
        String[] lineas = TABLA.strip().split("\n");
        String[] columnas = lineas[0].strip().split("\\s+");
        List<Caso> casos = new ArrayList<>();
        for (int i = 1; i < lineas.length; i++) {
            String[] celdas = lineas[i].strip().split("\\s+");
            for (int c = 1; c < columnas.length; c++) {
                casos.add(new Caso(ReservationProcessStatus.valueOf(celdas[0]), CatedraReservationStatus.valueOf(columnas[c]), celdas[c]));
            }
        }
        assertThat(casos).hasSize(ReservationProcessStatus.values().length * CatedraReservationStatus.values().length);
        return casos;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("casos")
    void laTablaDeConvergenciaSoloAvanzaYNuncaReabreUnFinal(Caso caso) {
        ReservationProcess proceso = ReservationProcessMatrix.procesoEn(caso.local(), 42);
        ConvergenceFact hecho = ReservationConvergence.factFor(reserva(caso.catedra(), 8251L, CONFIRMADA_EN, CANCELADA_EN, "Cambio de planes"), AHORA);

        TransitionResult resultado = hecho.applyTo(proceso, AHORA);

        switch (caso.esperado()) {
            case "-" -> {
                assertThat(hecho).isInstanceOf(ConvergenceFact.Nothing.class);
                assertThat(proceso.getStatus()).isEqualTo(caso.local());
            }
            case "FS" -> assertThat(resultado).isEqualTo(new NoEffect(caso.local(), NoEffectReason.FINAL_STATE));
            case "CO" -> assertThat(resultado).isEqualTo(new NoEffect(caso.local(), NoEffectReason.CONFIRMED_ONLY_CANCELS));
            case "NA" -> assertThat(resultado).isEqualTo(new NoEffect(caso.local(), NoEffectReason.NOT_APPLICABLE));
            default -> {
                ReservationProcessStatus destino = ReservationProcessStatus.valueOf(caso.esperado());
                assertThat(resultado).isEqualTo(new Applied(caso.local(), destino));
                assertThat(proceso.getStatusChangedAt()).isEqualTo(AHORA);
            }
        }
    }

    @Test
    void cu09_6_confirmadaGuardaElReservationIdYElConfirmedAtDeLaCatedra() {
        ReservationProcess proceso = ReservationProcessMatrix.procesoEn(ReservationProcessStatus.PHONE_SUBMITTED, 42);

        ConvergenceFact hecho = ReservationConvergence.factFor(reserva(CatedraReservationStatus.CONFIRMED, 8251L, CONFIRMADA_EN, null, null), AHORA);
        hecho.applyTo(proceso, AHORA);

        assertThat(hecho.degradations()).isEmpty();
        assertThat(proceso.getConfirmation()).contains(new Confirmation(8251, CONFIRMADA_EN));
    }

    @Test
    void cu09_6_unIntermedioQueLaCatedraInformaCanceladoGuardaTambienLaConfirmacion() {
        ReservationProcess proceso = ReservationProcessMatrix.procesoEn(ReservationProcessStatus.PHONE_SUBMITTED, 42);

        ConvergenceFact hecho = ReservationConvergence.factFor(
            reserva(CatedraReservationStatus.CANCELLED, 8251L, CONFIRMADA_EN, CANCELADA_EN, "Cambio de planes"),
            AHORA
        );
        hecho.applyTo(proceso, AHORA);

        assertThat(hecho.degradations()).isEmpty();
        assertThat(proceso.getConfirmation()).contains(new Confirmation(8251, CONFIRMADA_EN));
        assertThat(proceso.getCancellation()).contains(new Cancellation("Cambio de planes", CANCELADA_EN));
    }

    @Test
    void cu09_6_unConfirmadoQueLaCatedraInformaCanceladoConservaSuConfirmacion() {
        ReservationProcess proceso = ReservationProcessMatrix.procesoEn(ReservationProcessStatus.CONFIRMED, 42);

        ReservationConvergence.factFor(reserva(CatedraReservationStatus.CANCELLED, 9999L, CONFIRMADA_EN, CANCELADA_EN, null), AHORA).applyTo(proceso, AHORA);

        assertThat(proceso.getConfirmation()).contains(CONFIRMACION);
        assertThat(proceso.getCancellation()).contains(new Cancellation(null, CANCELADA_EN));
    }

    @Test
    void cu09_6a_laReservaFallidaDeLaCatedraConReservationIdNoEsUnaConfirmacion() {
        // Medido el 16/11: la 5858 expiró sin teléfono y figura FAILED con reservationId y sin confirmedAt.
        ConvergenceFact hecho = ReservationConvergence.factFor(reserva(CatedraReservationStatus.FAILED, 5858L, null, null, null), AHORA);

        assertThat(hecho).isEqualTo(new ConvergenceFact.Nothing(NothingReason.FAILED));
    }

    @Test
    void cu09_6a_phonePendingYUnEstadoDesconocidoNoCambianNada() {
        assertThat(ReservationConvergence.factFor(reserva(CatedraReservationStatus.PHONE_PENDING, 8251L, null, null, null), AHORA)).isEqualTo(
            new ConvergenceFact.Nothing(NothingReason.PHONE_PENDING)
        );
        assertThat(ReservationConvergence.factFor(reserva(CatedraReservationStatus.UNRECOGNIZED, 8251L, null, null, null), AHORA)).isEqualTo(
            new ConvergenceFact.Nothing(NothingReason.UNRECOGNIZED_STATUS)
        );
    }

    @Test
    void confirmadaSinConfirmedAtUsaElInstanteActualYLoInforma() {
        ConvergenceFact hecho = ReservationConvergence.factFor(reserva(CatedraReservationStatus.CONFIRMED, 8251L, null, null, null), AHORA);

        assertThat(hecho).isEqualTo(new ConvergenceFact.Confirm(new Confirmation(8251, AHORA), Set.of(Degradation.CONFIRMED_AT_MISSING)));
    }

    @Test
    void confirmadaSinReservationIdPositivoNoSePuedeAplicar() {
        assertThat(ReservationConvergence.factFor(reserva(CatedraReservationStatus.CONFIRMED, null, CONFIRMADA_EN, null, null), AHORA)).isEqualTo(
            new ConvergenceFact.Nothing(NothingReason.RESERVATION_ID_MISSING)
        );
        assertThat(ReservationConvergence.factFor(reserva(CatedraReservationStatus.CONFIRMED, 0L, CONFIRMADA_EN, null, null), AHORA)).isEqualTo(
            new ConvergenceFact.Nothing(NothingReason.RESERVATION_ID_MISSING)
        );
    }

    @Test
    void canceladaSinDatosDeConfirmacionNiCancelledAtSeCancelaIgualYLoInforma() {
        ReservationProcess proceso = ReservationProcessMatrix.procesoEn(ReservationProcessStatus.PHONE_REQUESTED, 42);

        ConvergenceFact hecho = ReservationConvergence.factFor(reserva(CatedraReservationStatus.CANCELLED, null, null, null, "   "), AHORA);
        hecho.applyTo(proceso, AHORA);

        assertThat(hecho.degradations()).containsExactlyInAnyOrder(Degradation.CANCELLED_AT_MISSING, Degradation.CONFIRMATION_DATA_MISSING);
        assertThat(proceso.getStatus()).isEqualTo(ReservationProcessStatus.CANCELLED);
        assertThat(proceso.getConfirmation()).isEmpty();
        // Un motivo en blanco es "sin motivo".
        assertThat(proceso.getCancellation()).contains(new Cancellation(null, AHORA));
    }

    @Test
    void unMotivoDeMasDe500CaracteresSeRecorta() {
        ConvergenceFact hecho = ReservationConvergence.factFor(reserva(CatedraReservationStatus.CANCELLED, 8251L, CONFIRMADA_EN, CANCELADA_EN, "x".repeat(501)), AHORA);

        assertThat(hecho.degradations()).containsExactly(Degradation.REASON_TRUNCATED);
        assertThat(((ConvergenceFact.CancelByReconciliation) hecho).cancellation().reason()).hasSize(500);
    }

    @Test
    void elToStringDeLaReservaNoLlevaElMotivo() {
        assertThat(reserva(CatedraReservationStatus.CANCELLED, 8251L, CONFIRMADA_EN, CANCELADA_EN, "motivo privado").toString()).doesNotContain("motivo privado");
    }

    @Test
    void laListaConItemsIgnoradosNoPruebaQueFalteUnaReserva() {
        CatedraReservation una = reserva(CatedraReservationStatus.CONFIRMED, 8251L, CONFIRMADA_EN, null, null);

        assertThat(new CatedraReservationList(List.of(una), 0).provesAbsence()).isTrue();
        assertThat(new CatedraReservationList(List.of(una), 1).provesAbsence()).isFalse();
        assertThat(new CatedraReservationList(List.of(una), 0).find("process-a92b47d0")).contains(una);
        assertThat(new CatedraReservationList(List.of(una), 0).find("otro")).isEqualTo(Optional.empty());
    }

    private static CatedraReservation reserva(
        CatedraReservationStatus estado,
        Long reservationId,
        Instant confirmedAt,
        Instant cancelledAt,
        String motivo
    ) {
        return new CatedraReservation(
            "process-a92b47d0",
            "user-42",
            reservationId,
            LocalDate.parse("2026-12-14"),
            LocalTime.parse("09:00"),
            estado,
            confirmedAt,
            cancelledAt,
            motivo
        );
    }
}
