package ar.edu.um.programacion2.turnos.reservation.domain.ports.out;

import ar.edu.um.programacion2.turnos.reservation.domain.model.PhoneSubmission;

/** Publica el teléfono en el topic de teléfono de la cátedra (CU-06 paso 6) y espera la confirmación del broker. */
public interface PhoneSubmissionPublisher {
    /**
     * Vuelve cuando el broker confirmó la escritura. Nunca se llama adentro de una transacción de base.
     *
     * @throws PhoneSubmissionException si no se publicó o no se sabe si se publicó
     */
    void publish(PhoneSubmission submission);
}
