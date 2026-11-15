package ar.edu.um.programacion2.turnos.reservation.domain.ports.out;

/**
 * No se publicó nada: no se pudo comprobar que el topic de teléfono existe (broker caído, sin ZeroTier) o no existe (un
 * nombre mal configurado). La cátedra seguro no lo recibió.
 */
public final class PhoneSubmissionNotSentException extends PhoneSubmissionException {

    public PhoneSubmissionNotSentException(String message, Throwable cause) {
        super(message, cause);
    }
}
