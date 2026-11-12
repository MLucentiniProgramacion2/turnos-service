package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Identificador local del proceso, el que ve la app (decisión del #10). Es un UUID aleatorio y no una secuencia: no deja
 * deducir cuántos procesos hay ni probar los IDs vecinos (los de otro usuario dan {@code 404}, DPT-19). El modelo no lo
 * genera: lo recibe del caso de uso, así los tests son deterministas.
 */
public record ReservationProcessId(UUID value) {
    public ReservationProcessId {
        Objects.requireNonNull(value, "Falta el identificador del proceso");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
