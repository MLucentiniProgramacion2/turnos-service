package ar.edu.um.programacion2.turnos.reservation.infrastructure.web.dto;

import jakarta.validation.constraints.Size;

/**
 * Cuerpo de {@code POST /api/reservation-processes/{id}/cancel} (CU-10). Es opcional, y {@code reason} también. El límite
 * es el de la cátedra (contrato §12): así nunca le llega un {@code 400} que el usuario podría haber evitado. El motivo
 * nunca va al log: el {@code toString()} no lo muestra.
 */
public record CancelReservationRequest(@Size(max = 500) String reason) {
    @Override
    public String toString() {
        return "CancelReservationRequest[reason=" + (reason == null ? "null" : "***") + "]";
    }
}
