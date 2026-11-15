package ar.edu.um.programacion2.turnos.reservation.infrastructure.messaging.listener;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * El cuerpo de un evento del topic de acciones (contrato §15.4 a §15.10), tal como llega: la unión de los campos de los
 * seis tipos, todos como texto o tipo envoltorio. Un campo que falta llega como {@code null} y lo decide
 * {@link ReservationEventMessageMapper}; los instantes se leen como texto para validarlos ahí y no depender de Jackson. Los
 * campos que no se usan ({@code groupId}, {@code schemaVersion}, {@code requiredField}...) y los nuevos se ignoran
 * (§15.1). Es package-private: los DTO no salen de este paquete.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record ReservationEventMessage(
    String eventId,
    String eventType,
    String reservationProcessId,
    String holdId,
    String requestEventId,
    String message,
    String expiresAt,
    String occurredAt,
    String reason,
    Long reservationId
) {}
