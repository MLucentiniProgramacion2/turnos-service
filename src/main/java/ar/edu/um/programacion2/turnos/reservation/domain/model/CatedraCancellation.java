package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * El {@code 200} de {@code POST /api/appointments/{reservationProcessId}/cancel} (contrato §12): la reserva quedó
 * {@code CANCELLED} en la cátedra. Si ya estaba cancelada, la cátedra devuelve el mismo estado (es idempotente).
 *
 * @param cancelledAt cuándo la canceló la cátedra
 */
public record CatedraCancellation(String reservationProcessId, Long reservationId, Instant cancelledAt) {
    public CatedraCancellation {
        Texts.requireText(reservationProcessId, "el reservationProcessId");
        Objects.requireNonNull(cancelledAt, "Falta el instante de cancelación");
    }
}
