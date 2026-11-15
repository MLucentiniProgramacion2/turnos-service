package ar.edu.um.programacion2.turnos.reservation.infrastructure.messaging.producer;

/**
 * El cuerpo de {@code AdditionalInformationSubmitted} tal como lo pide el contrato (§15.5): todos los campos son
 * obligatorios, y {@code eventType} y {@code schemaVersion} tienen exactamente estos valores. Jackson escribe los
 * componentes de un record en este orden.
 * <p>
 * Es package-private y no sale de este paquete. Su {@code toString()} no muestra el teléfono, por si alguien lo loguea.
 */
record AdditionalInformationSubmittedMessage(
    String eventId,
    String eventType,
    String groupId,
    String reservationProcessId,
    String holdId,
    String requestEventId,
    Information information,
    String occurredAt,
    int schemaVersion
) {
    static final String EVENT_TYPE = "AdditionalInformationSubmitted";
    static final int SCHEMA_VERSION = 1;

    record Information(String phoneNumber) {
        @Override
        public String toString() {
            return "Information[phoneNumber=***]";
        }
    }
}
