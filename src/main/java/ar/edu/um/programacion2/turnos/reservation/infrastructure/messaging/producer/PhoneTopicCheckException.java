package ar.edu.um.programacion2.turnos.reservation.infrastructure.messaging.producer;

/**
 * La comprobación del topic de teléfono no salió bien. Queda dentro del adaptador: el envío la traduce a
 * {@code PhoneSubmissionNotSentException} (el puerto) y la comprobación de fondo solo la registra y reintenta.
 */
final class PhoneTopicCheckException extends RuntimeException {

    private final boolean topicMissing;
    private final String reason;

    /**
     * @param topicMissing el broker respondió que el topic no existe: un error de configuración nuestro
     * @param reason la falla en una línea, para el log (sin el nombre del topic ni la dirección del broker)
     */
    PhoneTopicCheckException(boolean topicMissing, String reason, Throwable cause) {
        super(reason, cause);
        this.topicMissing = topicMissing;
        this.reason = reason;
    }

    boolean topicMissing() {
        return topicMissing;
    }

    String reason() {
        return reason;
    }
}
