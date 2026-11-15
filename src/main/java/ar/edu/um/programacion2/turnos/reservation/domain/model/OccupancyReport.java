package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Las ocupaciones de un profesional en un rango de fechas (contrato §8). Una lista vacía es una respuesta válida. */
public record OccupancyReport(long professionalId, LocalDate from, LocalDate to, List<Occupancy> occupancies) {
    public OccupancyReport {
        Objects.requireNonNull(from, "Falta la fecha desde");
        Objects.requireNonNull(to, "Falta la fecha hasta");
        occupancies = List.copyOf(Objects.requireNonNull(occupancies, "Faltan las ocupaciones"));
    }

    /**
     * Si el turno figura como no disponible (CU-03 paso 7): hay una ocupación con su misma fecha y hora de inicio, sea
     * {@code HELD} o {@code CONFIRMED}. Se comparan las horas ya convertidas, así {@code 09:30} y {@code 09:30:00} son la
     * misma. Una ocupación que no coincide con el inicio de ningún turno no ocupa nada.
     */
    public boolean isOccupied(AppointmentSlot slot) {
        Objects.requireNonNull(slot, "Falta el turno");
        return occupancies.stream().anyMatch(occupancy -> occupancy.date().equals(slot.date()) && occupancy.startTime().equals(slot.startTime()));
    }
}
