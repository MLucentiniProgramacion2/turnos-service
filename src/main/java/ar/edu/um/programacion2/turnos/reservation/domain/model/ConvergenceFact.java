package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * El hecho que corresponde a lo que informa la cátedra sobre una reserva ({@link ReservationConvergence}). Es
 * {@code sealed}: quien lo aplica decide con un {@code switch} completo, o lo aplica directamente con
 * {@link #applyTo(ReservationProcess, Instant)}, y el modelo decide según el estado local (un final no cambia, un
 * {@code CONFIRMED} solo se cancela, solo se avanza).
 * <p>
 * {@link #degradations()} dice qué dato de la cátedra faltó o se reemplazó, para que el caso de uso lo deje en el log (el
 * dominio no loguea).
 */
public sealed interface ConvergenceFact {
    /** Qué dato de la cátedra se reemplazó o se descartó al armar el hecho. */
    enum Degradation {
        /** {@code CONFIRMED} sin {@code confirmedAt}: se usa el instante actual. */
        CONFIRMED_AT_MISSING,
        /** {@code CANCELLED} sin {@code cancelledAt}: se usa el instante actual. */
        CANCELLED_AT_MISSING,
        /** {@code CANCELLED} sin {@code reservationId} positivo o sin {@code confirmedAt}: se cancela sin la confirmación. */
        CONFIRMATION_DATA_MISSING,
        /** El motivo de la cancelación supera los 500 caracteres de la cátedra: se recorta. */
        REASON_TRUNCATED,
    }

    /** Por qué no hay nada que aplicar. */
    enum NothingReason {
        /** La cátedra espera el teléfono: el cierre por vencimiento es de CU-11 (CU-09 6a). */
        PHONE_PENDING,
        /** La cátedra lo cerró sin confirmar: lo pasa a {@code EXPIRED} CU-11, con su margen (CU-09 6a). */
        FAILED,
        /** Un estado que el contrato no lista: no se sabe qué hacer. */
        UNRECOGNIZED_STATUS,
        /** {@code CONFIRMED} sin {@code reservationId} positivo: el modelo (y el {@code CHECK}) lo exigen. */
        RESERVATION_ID_MISSING,
    }

    TransitionResult applyTo(ReservationProcess process, Instant now);

    Set<Degradation> degradations();

    /** La cátedra la tiene {@code CONFIRMED}. */
    record Confirm(Confirmation confirmation, Set<Degradation> degradations) implements ConvergenceFact {
        public Confirm {
            Objects.requireNonNull(confirmation, "Falta la confirmación");
            degradations = copy(degradations);
        }

        @Override
        public TransitionResult applyTo(ReservationProcess process, Instant now) {
            return process.confirmed(confirmation, now);
        }
    }

    /** La cátedra la tiene {@code CANCELLED}. {@code knownConfirmation} es {@code null} si no vino completa. */
    record CancelByReconciliation(Cancellation cancellation, Confirmation knownConfirmation, Set<Degradation> degradations)
        implements ConvergenceFact {
        public CancelByReconciliation {
            Objects.requireNonNull(cancellation, "Falta la cancelación");
            degradations = copy(degradations);
        }

        @Override
        public TransitionResult applyTo(ReservationProcess process, Instant now) {
            return process.cancelledByReconciliation(cancellation, knownConfirmation, now);
        }
    }

    /** Nada que aplicar: el proceso queda como está. */
    record Nothing(NothingReason reason) implements ConvergenceFact {
        public Nothing {
            Objects.requireNonNull(reason, "Falta el motivo");
        }

        @Override
        public TransitionResult applyTo(ReservationProcess process, Instant now) {
            return new TransitionResult.NoEffect(process.getStatus(), NoEffectReason.NOT_APPLICABLE);
        }

        @Override
        public Set<Degradation> degradations() {
            return Set.of();
        }
    }

    private static Set<Degradation> copy(Set<Degradation> degradations) {
        Objects.requireNonNull(degradations, "Faltan las degradaciones");
        return degradations.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(degradations));
    }
}
