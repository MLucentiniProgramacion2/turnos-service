package ar.edu.um.programacion2.turnos.reservation.infrastructure.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Respuesta de CU-03 (contrato en {@code docs/api-rest.md}). {@code days} trae todos los días del rango, también los que
 * no tienen turnos. {@code timeZone} es la zona de atención: las fechas y horas son locales de esa zona.
 */
public record AvailabilityResponse(Professional professional, LocalDate from, LocalDate to, String timeZone, List<Day> days) {
    public record Professional(long id, String firstName, String lastName, Category category) {}

    /** La categoría como la informa el catálogo; que esté deshabilitada no cambia los turnos. */
    public record Category(long id, String name, boolean enabled) {}

    public record Day(LocalDate date, List<Slot> slots) {}

    /**
     * Las horas van siempre como {@code HH:mm:ss}, igual que en el catálogo: fijo con {@code @JsonFormat}, porque el valor
     * por defecto de Jackson omite los segundos en cero en algunos formatos y agregaría fracciones si las hubiera.
     */
    public record Slot(@JsonFormat(pattern = "HH:mm:ss") LocalTime startTime, @JsonFormat(pattern = "HH:mm:ss") LocalTime endTime) {}
}
