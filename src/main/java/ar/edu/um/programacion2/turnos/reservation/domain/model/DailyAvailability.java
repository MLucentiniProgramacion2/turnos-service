package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Los turnos libres de un día, ordenados por hora de inicio. Una lista vacía es un día sin turnos. */
public record DailyAvailability(LocalDate date, List<AppointmentSlot> slots) {
    public DailyAvailability {
        Objects.requireNonNull(date, "Falta la fecha");
        slots = List.copyOf(Objects.requireNonNull(slots, "Faltan los turnos"));
    }
}
