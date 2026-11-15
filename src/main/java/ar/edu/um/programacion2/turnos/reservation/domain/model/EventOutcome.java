package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.util.Objects;

/**
 * Qué pasó al procesar un evento de la cátedra (CU-05 y CU-07). Es {@code sealed}: el listener decide el nivel del log
 * con un {@code switch} completo. Ninguno es un error: todos confirman el offset. En todos, menos {@link Duplicate}, el
 * {@code eventId} quedó registrado en la misma transacción (DPT-15), también cuando no tuvo efecto.
 */
public sealed interface EventOutcome {
    /** El proceso cambió de estado. */
    record Applied(ReservationProcessStatus from, ReservationProcessStatus to) implements EventOutcome {
        public Applied {
            Objects.requireNonNull(from, "Falta el estado de origen");
            Objects.requireNonNull(to, "Falta el estado nuevo");
        }
    }

    /** El evento llegó a un proceso que no lo admite: un duplicado con otro {@code eventId}, un tardío o uno que no corresponde. */
    record NoEffect(ReservationProcessStatus state, NoEffectReason reason) implements EventOutcome {
        public NoEffect {
            Objects.requireNonNull(state, "Falta el estado");
            Objects.requireNonNull(reason, "Falta el motivo");
        }
    }

    /** El {@code eventId} ya estaba registrado: entrega repetida (contrato §18.1). No se hizo nada. */
    record Duplicate() implements EventOutcome {}

    /** No hay un proceso local con ese {@code reservationProcessId} (CU-05 3.2a, CU-07 2.2a). No se crea nada. */
    record ProcessNotFound() implements EventOutcome {}
}
