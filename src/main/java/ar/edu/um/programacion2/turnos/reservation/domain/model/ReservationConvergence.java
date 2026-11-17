package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * La convergencia hacia lo que informa la cátedra (CU-09 paso 6, reusada por CU-11 3a y 3b): qué hecho corresponde a una
 * reserva de {@code GET /api/appointments}.
 * <p>
 * <strong>Decide solo por el estado de la cátedra.</strong> Lo que depende del estado local ya lo sabe el modelo: un final
 * no cambia, un {@code CONFIRMED} solo se cancela y solo se avanza (DPT-05). Así esta tabla es corta y no repite la
 * máquina de estados:
 * <table>
 * <tr><th>Cátedra</th><th>Hecho</th></tr>
 * <tr><td>{@code CONFIRMED}</td><td>{@code confirmed(reservationId, confirmedAt)}</td></tr>
 * <tr><td>{@code CANCELLED}</td><td>{@code cancelledByReconciliation(motivo, cancelledAt, confirmación si vino)}</td></tr>
 * <tr><td>{@code PHONE_PENDING}, {@code FAILED}, desconocido</td><td>ninguno (CU-09 6a: el cierre es de CU-11)</td></tr>
 * </table>
 * Como en CU-07, se degrada lo mínimo: un dato que falta y no es imprescindible se reemplaza y se informa; solo un
 * {@code CONFIRMED} sin {@code reservationId} no se puede aplicar (el modelo y el {@code CHECK} lo exigen).
 */
public final class ReservationConvergence {

    private ReservationConvergence() {}

    /**
     * @param now el instante que reemplaza a un {@code confirmedAt} o {@code cancelledAt} ausente
     */
    public static ConvergenceFact factFor(CatedraReservation reservation, Instant now) {
        Objects.requireNonNull(reservation, "Falta la reserva de la cátedra");
        Objects.requireNonNull(now, "Falta el instante actual");
        Set<ConvergenceFact.Degradation> degradations = EnumSet.noneOf(ConvergenceFact.Degradation.class);
        return switch (reservation.status()) {
            case CONFIRMED -> {
                if (!hasReservationId(reservation)) {
                    yield new ConvergenceFact.Nothing(ConvergenceFact.NothingReason.RESERVATION_ID_MISSING);
                }
                Instant confirmedAt = reservation.confirmedAt();
                if (confirmedAt == null) {
                    degradations.add(ConvergenceFact.Degradation.CONFIRMED_AT_MISSING);
                    confirmedAt = now;
                }
                yield new ConvergenceFact.Confirm(new Confirmation(reservation.reservationId(), confirmedAt), degradations);
            }
            case CANCELLED -> {
                Instant cancelledAt = reservation.cancelledAt();
                if (cancelledAt == null) {
                    degradations.add(ConvergenceFact.Degradation.CANCELLED_AT_MISSING);
                    cancelledAt = now;
                }
                Confirmation known = null;
                if (hasReservationId(reservation) && reservation.confirmedAt() != null) {
                    known = new Confirmation(reservation.reservationId(), reservation.confirmedAt());
                } else {
                    degradations.add(ConvergenceFact.Degradation.CONFIRMATION_DATA_MISSING);
                }
                String reason = reason(reservation.cancellationReason(), degradations);
                yield new ConvergenceFact.CancelByReconciliation(new Cancellation(reason, cancelledAt), known, degradations);
            }
            case PHONE_PENDING -> new ConvergenceFact.Nothing(ConvergenceFact.NothingReason.PHONE_PENDING);
            case FAILED -> new ConvergenceFact.Nothing(ConvergenceFact.NothingReason.FAILED);
            case UNRECOGNIZED -> new ConvergenceFact.Nothing(ConvergenceFact.NothingReason.UNRECOGNIZED_STATUS);
        };
    }

    private static boolean hasReservationId(CatedraReservation reservation) {
        return reservation.reservationId() != null && reservation.reservationId() > 0;
    }

    /** En blanco es "sin motivo"; más de 500 caracteres se recorta (es el máximo de la cátedra, §12), como en CU-07. */
    private static String reason(String raw, Set<ConvergenceFact.Degradation> degradations) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.length() > Cancellation.MAX_REASON_LENGTH) {
            degradations.add(ConvergenceFact.Degradation.REASON_TRUNCATED);
            return raw.substring(0, Cancellation.MAX_REASON_LENGTH);
        }
        return raw;
    }
}
