package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Un horario semanal habilitado, tal como lo publica la cátedra y lo informa el catálogo: horas locales de atención.
 * <p>
 * No exige {@code startTime < endTime} ni duración positiva: el contrato no lo garantiza, y un horario así no es un error
 * del catálogo sino un dato que no genera turnos (intervalo vacío, como en el #27 del catálogo). Lo decide la generación
 * de turnos de CU-03 y CU-04.
 */
public record WeeklySchedule(long id, DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime, int slotDurationMinutes) {
    public WeeklySchedule {
        Objects.requireNonNull(dayOfWeek, "Falta el día de la semana");
        Objects.requireNonNull(startTime, "Falta la hora de inicio");
        Objects.requireNonNull(endTime, "Falta la hora de fin");
    }
}
