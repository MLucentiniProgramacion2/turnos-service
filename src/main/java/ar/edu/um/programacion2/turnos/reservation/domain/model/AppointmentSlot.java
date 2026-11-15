package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
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

    /**
     * Si el turno ya empezó en ese momento de la zona de atención (DPT-10): un turno que empieza justo ahora ya empezó.
     * CU-03 no lo ofrece y CU-04 no lo reserva.
     */
    public boolean hasStartedAt(LocalDateTime localNow) {
        return !date.atTime(startTime).isAfter(Objects.requireNonNull(localNow, "Falta la fecha y hora actual"));
    }
}
