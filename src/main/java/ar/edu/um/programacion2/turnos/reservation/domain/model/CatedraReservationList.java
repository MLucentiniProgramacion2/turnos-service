package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Todas las reservas de un pedido a {@code GET /api/appointments}, con todas sus páginas.
 *
 * @param ignoredItems cuántos ítems no se pudieron leer (sin {@code reservationProcessId}, o con una fecha u hora ilegible)
 *     y se dejaron afuera. Con alguno ignorado, la lista <strong>no prueba que una reserva falte</strong>: podría ser
 *     justo la ignorada (CU-11 3a y 3b).
 */
public record CatedraReservationList(List<CatedraReservation> items, int ignoredItems) {
    public CatedraReservationList {
        items = List.copyOf(Objects.requireNonNull(items, "Faltan las reservas"));
        if (ignoredItems < 0) {
            throw new IllegalArgumentException("Los ítems ignorados no pueden ser negativos");
        }
    }

    /** La reserva de ese proceso de la cátedra, si está en la lista. */
    public Optional<CatedraReservation> find(String reservationProcessId) {
        return items.stream().filter(item -> item.reservationProcessId().equals(reservationProcessId)).findFirst();
    }

    /** Si la ausencia de una reserva en esta lista prueba que la cátedra no la tiene. */
    public boolean provesAbsence() {
        return ignoredItems == 0;
    }
}
