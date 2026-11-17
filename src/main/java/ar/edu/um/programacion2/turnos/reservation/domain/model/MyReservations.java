package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.util.Objects;

/**
 * La respuesta de "Mis reservas" (CU-09): una página de procesos propios, como quedaron después de converger con la
 * cátedra.
 *
 * @param catedraUpToDate {@code false} solo si hacía falta preguntarle a la cátedra y no se pudo (4b, 4c), o si no se pudo
 *     guardar lo que informó (6b agotado): esos procesos se muestran con el estado local. {@code true} también cuando no
 *     hizo falta preguntar (3a, 4a): lo local ya es todo lo que hay que saber.
 */
public record MyReservations(ResultPage<ReservationProcess> page, boolean catedraUpToDate) {
    public MyReservations {
        Objects.requireNonNull(page, "Falta la página");
    }
}
