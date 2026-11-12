package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Estados del proceso de reserva local (DPT-05). Hay tres clases:
 * <ul>
 * <li><strong>Intermedios</strong>: el proceso está en curso y todavía puede recibir eventos de la cátedra. Un usuario
 * tiene como máximo uno (DPT-17, índice único parcial).</li>
 * <li><strong>Estable</strong>: {@link #CONFIRMED}. No es final: su única salida es {@link #CANCELLED}.</li>
 * <li><strong>Finales</strong>: ningún evento los modifica y un evento tardío no los reabre.</li>
 * </ul>
 * Se guardan por nombre ({@code EnumType.STRING}): renombrar uno exige un changeset nuevo que actualice las filas, el
 * {@code CHECK} de {@code status} y el índice único parcial. Si se olvida el índice, lo detecta
 * {@code ReservationProcessSchemaIT}.
 */
public enum ReservationProcessStatus {
    CREATING_HOLD(Kind.INTERMEDIATE),
    HOLD_CREATED(Kind.INTERMEDIATE),
    WAITING_FOR_PHONE_REQUEST(Kind.INTERMEDIATE),
    PHONE_REQUESTED(Kind.INTERMEDIATE),
    PHONE_SUBMITTED(Kind.INTERMEDIATE),
    PHONE_REJECTED(Kind.INTERMEDIATE),
    CONFIRMED(Kind.STABLE),
    CANCELLED(Kind.FINAL),
    EXPIRED(Kind.FINAL),
    INVALID(Kind.FINAL),
    FAILED(Kind.FINAL);

    private enum Kind {
        INTERMEDIATE,
        STABLE,
        FINAL,
    }

    private final Kind kind;

    ReservationProcessStatus(Kind kind) {
        this.kind = kind;
    }

    public boolean isIntermediate() {
        return kind == Kind.INTERMEDIATE;
    }

    public boolean isFinal() {
        return kind == Kind.FINAL;
    }

    /** Los intermedios, en el orden del enum: los que revisa CU-11 y los que cubre el índice único parcial. */
    public static Set<ReservationProcessStatus> intermediates() {
        return Arrays.stream(values())
            .filter(ReservationProcessStatus::isIntermediate)
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(ReservationProcessStatus.class)));
    }
}
