package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * El turno del proceso: profesional, fecha y horas locales de atención (contrato §4).
 * <p>
 * Exige {@code startTime < endTime}: el turno sale de un horario semanal y un horario con inicio mayor o igual que el fin
 * no genera turnos (es vacío, como en el #27 del catálogo). Por eso un turno nunca cruza la medianoche ni termina a las
 * 00:00.
 */
public record AppointmentSlot(long professionalId, LocalDate date, LocalTime startTime, LocalTime endTime) {
    public AppointmentSlot {
        if (professionalId <= 0) {
            throw new IllegalArgumentException("El id del profesional tiene que ser positivo");
        }
        Objects.requireNonNull(date, "Falta la fecha del turno");
        Objects.requireNonNull(startTime, "Falta la hora de inicio");
        Objects.requireNonNull(endTime, "Falta la hora de fin");
        if (!startTime.isBefore(endTime)) {
            throw new IllegalArgumentException("El turno tiene que terminar después de empezar: " + startTime + "-" + endTime);
        }
    }
}
