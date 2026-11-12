package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.util.Objects;

/**
 * Resultado de aplicar un hecho al proceso. Es {@code sealed} para que el caso de uso decida con un {@code switch}
 * completo: con {@link Applied} guarda y registra el cambio; con {@link NoEffect} registra el evento como procesado sin
 * efecto y, según el motivo, deja una advertencia en el log (por ejemplo, un {@code AppointmentProcessInvalid} sobre un
 * proceso {@code CONFIRMED}, CU-07 2.3b).
 */
public sealed interface TransitionResult {
    /** El proceso cambió de estado. */
    record Applied(ReservationProcessStatus from, ReservationProcessStatus to) implements TransitionResult {
        public Applied {
            Objects.requireNonNull(from, "Falta el estado de origen");
            Objects.requireNonNull(to, "Falta el estado nuevo");
        }
    }

    /** El proceso no cambió de estado. */
    record NoEffect(ReservationProcessStatus state, NoEffectReason reason) implements TransitionResult {
        public NoEffect {
            Objects.requireNonNull(state, "Falta el estado");
            Objects.requireNonNull(reason, "Falta el motivo");
        }
    }
}
