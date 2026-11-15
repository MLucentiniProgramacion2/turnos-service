package ar.edu.um.programacion2.turnos.reservation.domain.ports.out;

/**
 * El teléfono no se pudo publicar con confirmación del broker (CU-06 6a). Es {@code sealed}: CU-06 decide con un
 * {@code switch} completo. En los dos casos se responde lo mismo al usuario y el proceso no cambia; la diferencia es para
 * el log y para saber si la cátedra pudo haberlo recibido.
 */
public abstract sealed class PhoneSubmissionException
    extends RuntimeException
    permits PhoneSubmissionNotSentException, PhoneSubmissionUnconfirmedException {

    protected PhoneSubmissionException(String message, Throwable cause) {
        super(message, cause);
    }
}
