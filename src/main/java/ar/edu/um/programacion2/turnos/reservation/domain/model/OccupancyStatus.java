package ar.edu.um.programacion2.turnos.reservation.domain.model;

/** Estado de una ocupación (contrato §8). Si un turno aparece en los dos, la cátedra informa {@code CONFIRMED}. */
public enum OccupancyStatus {
    CONFIRMED,
    HELD,
}
