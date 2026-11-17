package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * El pedido del listado administrativo (CU-12). Es el único lugar donde llega un id de usuario desde afuera: es un filtro
 * de un administrador sobre datos de todos, no una identidad (DPT-14).
 *
 * @param userId el dueño, o {@code null} para todos
 * @param statuses los estados pedidos; vacío es "todos"
 */
public record AdminProcessQuery(Long userId, Set<ReservationProcessStatus> statuses, PageQuery page) {
    public AdminProcessQuery {
        if (userId != null && userId <= 0) {
            throw new IllegalArgumentException("El id del usuario tiene que ser positivo");
        }
        Objects.requireNonNull(statuses, "Faltan los estados (vacío es todos)");
        statuses = statuses.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(statuses));
        Objects.requireNonNull(page, "Falta la página");
    }
}
