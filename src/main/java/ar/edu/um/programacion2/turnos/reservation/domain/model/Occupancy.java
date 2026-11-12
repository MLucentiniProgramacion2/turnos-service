package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Una posición no disponible según la cátedra (contrato §8): un turno confirmado o retenido por un hold vigente. CU-03 la
 * descarta de los turnos libres, sea {@code HELD} o {@code CONFIRMED}. {@code expiresAt} solo tiene valor en {@code HELD}.
 */
public record Occupancy(LocalDate date, LocalTime startTime, OccupancyStatus status, Instant expiresAt) {
    public Occupancy {
        Objects.requireNonNull(date, "Falta la fecha de la ocupación");
        Objects.requireNonNull(startTime, "Falta la hora de la ocupación");
        Objects.requireNonNull(status, "Falta el estado de la ocupación");
    }
}
