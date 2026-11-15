package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.util.Objects;

/** Resultado de informar el teléfono (CU-06). Es {@code sealed}: el controller decide la respuesta con un {@code switch} completo. */
public sealed interface SubmitPhoneResult {
    /**
     * Paso 8: el evento quedó publicado y el {@code eventId} guardado. El estado es el actual: {@code PHONE_SUBMITTED}, o
     * uno más avanzado si el resultado llegó por Kafka antes de guardar (7a).
     */
    record Submitted(ReservationProcess process) implements SubmitPhoneResult {
        public Submitted {
            Objects.requireNonNull(process, "Falta el proceso");
        }
    }

    /** 2b: el proceso no existe o es de otro usuario: la misma respuesta (DPT-19). */
    record NotFound() implements SubmitPhoneResult {}

    /** 3a. */
    record NotRequestedYet() implements SubmitPhoneResult {}

    /** 3b. */
    record AlreadySubmitted() implements SubmitPhoneResult {}

    /** 3c: con el estado actual, que la app puede mostrar. */
    record Closed(ReservationProcessStatus status) implements SubmitPhoneResult {
        public Closed {
            Objects.requireNonNull(status, "Falta el estado");
        }
    }

    /** 3d: pasó {@code expiresAt}. El proceso no se modificó. */
    record DeadlinePassed() implements SubmitPhoneResult {}

    /** 4a: el teléfono no cumple lo que acepta la cátedra. No se envió nada. */
    record InvalidPhone() implements SubmitPhoneResult {}

    /** 6a: Kafka no confirmó. El proceso no cambió; el usuario puede reenviar, con un {@code eventId} nuevo. */
    record Unconfirmed() implements SubmitPhoneResult {}

    /** 7b: el evento se publicó pero no se pudo guardar. El proceso avanza cuando llegue el resultado. */
    record SaveFailed() implements SubmitPhoneResult {}
}
