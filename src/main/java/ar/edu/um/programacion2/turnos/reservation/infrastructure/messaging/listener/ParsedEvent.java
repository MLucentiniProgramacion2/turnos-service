package ar.edu.um.programacion2.turnos.reservation.infrastructure.messaging.listener;

import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationEvent;

/**
 * Lo que sale de leer un mensaje del topic de acciones. Es {@code sealed}: el listener decide con un {@code switch}
 * completo qué hace con cada caso. Solo {@link Event} llega a un caso de uso; los demás se confirman sin reintento
 * (DPT-22) y sin registrar el {@code eventId}.
 */
sealed interface ParsedEvent {
    /** Un evento válido de un tipo que turnos procesa. */
    record Event(ReservationEvent event) implements ParsedEvent {}

    /**
     * Uno de los cinco resultados de CU-07 ({@code AppointmentConfirmed}...), que todavía no se procesan (issue #12, parte
     * 5). No se registran: cuando se procesen, una reentrega tiene que poder aplicarlos.
     */
    record NotYetSupported(String eventType, String eventId) implements ParsedEvent {}

    /** Un {@code eventType} que el contrato v1 no define. */
    record UnknownType(String eventId) implements ParsedEvent {}

    /**
     * Ilegible, vacío o sin un campo obligatorio. {@code detail} dice qué falta, sin repetir ningún valor del mensaje.
     *
     * @param eventId el {@code eventId}, si se pudo leer; si no, {@code null}
     */
    record Malformed(String eventId, String detail) implements ParsedEvent {}
}
