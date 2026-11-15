package ar.edu.um.programacion2.turnos.reservation.domain.ports.out;

import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessId;

/**
 * De dónde sale el identificador de un proceso nuevo. Es un puerto para que el caso de uso no llame a
 * {@code UUID.randomUUID()}: en producción es un UUID v4 aleatorio (decisión del #10) y en los tests, uno fijo.
 */
@FunctionalInterface
public interface ReservationProcessIdGenerator {
    ReservationProcessId next();
}
