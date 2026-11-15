package ar.edu.um.programacion2.turnos.reservation.domain.ports.out;

/**
 * Se intentó publicar y el broker no confirmó a tiempo, o respondió con un error. Es un resultado desconocido, como un
 * timeout de un {@code POST} (contrato §18.4): el evento pudo haber llegado. No se reintenta solo; el usuario puede volver a
 * enviar, con un {@code eventId} nuevo (CU-06 6a).
 */
public final class PhoneSubmissionUnconfirmedException extends PhoneSubmissionException {

    public PhoneSubmissionUnconfirmedException(String message, Throwable cause) {
        super(message, cause);
    }
}
