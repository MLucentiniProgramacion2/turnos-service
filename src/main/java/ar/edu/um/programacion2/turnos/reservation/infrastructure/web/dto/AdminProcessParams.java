package ar.edu.um.programacion2.turnos.reservation.infrastructure.web.dto;

import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Objects;

/**
 * Parámetros de {@code GET /api/admin/reservation-processes} (CU-12), validados con {@code @Valid}: un valor fuera de rango
 * o un estado desconocido dan {@code 400 VALIDATION_ERROR} sin repetir el valor.
 *
 * @param userId el dueño ({@code id} interno), o ninguno para todos
 * @param status uno o varios estados, separados por coma o repitiendo el parámetro
 */
public record AdminProcessParams(@Positive Long userId, List<ReservationProcessStatus> status, @Min(0) @Max(10_000) Integer page, @Min(1) @Max(100) Integer size) {
    public static final int DEFAULT_SIZE = 20;

    public int pageOrDefault() {
        return page == null ? 0 : page;
    }

    public int sizeOrDefault() {
        return size == null ? DEFAULT_SIZE : size;
    }

    public List<ReservationProcessStatus> statusOrEmpty() {
        return status == null ? List.of() : status.stream().filter(Objects::nonNull).toList();
    }
}
