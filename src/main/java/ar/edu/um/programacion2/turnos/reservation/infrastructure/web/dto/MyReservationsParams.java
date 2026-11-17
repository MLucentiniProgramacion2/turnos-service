package ar.edu.um.programacion2.turnos.reservation.infrastructure.web.dto;

import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;

/**
 * Parámetros de {@code GET /api/reservation-processes} (CU-09), juntos en un record para validarlos con {@code @Valid},
 * como {@code AvailabilityParams}: un estado desconocido o una página fuera de rango dan {@code 400 VALIDATION_ERROR} sin
 * repetir el valor.
 *
 * @param status uno o varios estados locales, separados por coma o repitiendo el parámetro; sin ninguno, todos
 * @param page número de página desde 0; por defecto 0
 * @param size tamaño de página de 1 a {@value #MAX_SIZE}; por defecto {@value #DEFAULT_SIZE}
 */
public record MyReservationsParams(List<ReservationProcessStatus> status, @Min(0) @Max(10_000) Integer page, @Min(1) @Max(MAX_SIZE) Integer size) {
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 50;

    public int pageOrDefault() {
        return page == null ? 0 : page;
    }

    public int sizeOrDefault() {
        return size == null ? DEFAULT_SIZE : size;
    }

    public List<ReservationProcessStatus> statusOrEmpty() {
        return status == null ? List.of() : status.stream().filter(java.util.Objects::nonNull).toList();
    }
}
