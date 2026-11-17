package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * El pedido de "Mis reservas" (CU-09): el usuario sale del token (DPT-21), nunca del pedido.
 *
 * @param statuses los estados locales pedidos; vacío es "todos"
 */
public record MyReservationsQuery(long userId, Set<ReservationProcessStatus> statuses, PageQuery page) {
    public MyReservationsQuery {
        if (userId <= 0) {
            throw new IllegalArgumentException("El id del usuario tiene que ser positivo");
        }
        Objects.requireNonNull(statuses, "Faltan los estados (vacío es todos)");
        statuses = statuses.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(statuses));
        Objects.requireNonNull(page, "Falta la página");
    }
}
