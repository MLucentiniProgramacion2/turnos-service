package ar.edu.um.programacion2.turnos.reservation.domain.ports.out;

/**
 * De dónde sale el {@code eventId} de cada evento que publica turnos. Es un puerto para que el caso de uso no llame a
 * {@code UUID.randomUUID()}: en producción es un UUID aleatorio, nuevo en cada envío (contrato §15.5); en los tests, uno
 * fijo.
 */
@FunctionalInterface
public interface EventIdGenerator {
    String next();
}
