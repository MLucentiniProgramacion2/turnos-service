package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Lo que decide la reconciliación (CU-11) sobre un proceso intermedio. Es {@code sealed}: el caso de uso decide con un
 * {@code switch} completo. Las decisiones que se aplican al proceso ({@link Apply}) llevan el hecho del modelo y un motivo
 * para el log; el resto no lo modifica.
 */
public sealed interface ReconciliationAction {
    /** Por qué no se hace nada en esta ejecución. */
    enum WaitReason {
        /** Todavía no pasó el tiempo que hay que esperar: la antigüedad mínima (DPT-24) o {@code expiresAt} + margen (DPT-09). */
        NOT_DUE,
        /** No es un intermedio (un evento lo cerró mientras tanto, 5a): no hay nada que reconciliar. */
        NOT_INTERMEDIATE,
        /** {@code HOLD_CREATED} ausente de la cátedra, con {@code expiresAt} pasado pero antes del margen (3a). */
        EXPIRING,
        /** La cátedra lo tiene en {@code PHONE_PENDING} y todavía no pasó la espera máxima (3b). */
        PHONE_PENDING,
        /** La lista tiene ítems que no se pudieron leer: no prueba que el proceso falte. */
        LIST_INCOMPLETE,
        /** La cátedra informa un estado que el contrato no lista, o datos con los que no se puede converger. */
        UNUSABLE_CATEDRA_DATA,
    }

    /** Un hecho que se aplica al proceso, con el motivo para el log (un código, sin datos personales). */
    record Apply(ProcessFact fact, String reason) implements ReconciliationAction {
        public Apply {
            Objects.requireNonNull(fact, "Falta el hecho");
            Texts.requireText(reason, "el motivo");
        }
    }

    /** 3a: la cátedra no tiene el confirm y no pasó {@code expiresAt}: se reintenta con los datos guardados (DPT-07). */
    record RetryConfirm() implements ReconciliationAction {}

    /** Nada que hacer en esta ejecución. */
    record Wait(WaitReason reason) implements ReconciliationAction {
        public Wait {
            Objects.requireNonNull(reason, "Falta el motivo");
        }
    }

    /** Un hecho del modelo, ya armado con sus datos: el caso de uso solo le pasa el proceso releído y el instante. */
    @FunctionalInterface
    interface ProcessFact {
        TransitionResult applyTo(ReservationProcess process, Instant now);
    }
}
