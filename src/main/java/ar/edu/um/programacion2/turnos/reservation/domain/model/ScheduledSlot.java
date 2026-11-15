package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.util.Objects;

/**
 * Un turno generado a partir de un horario semanal vigente, con la duración de ese horario. La duración no sale de restar
 * las horas: es el dato del catálogo, que CU-04 guarda como histórico (DPT-12).
 */
public record ScheduledSlot(AppointmentSlot slot, int slotDurationMinutes) {
    public ScheduledSlot {
        Objects.requireNonNull(slot, "Falta el turno");
        if (slotDurationMinutes <= 0) {
            throw new IllegalArgumentException("La duración del turno tiene que ser positiva");
        }
    }
}
