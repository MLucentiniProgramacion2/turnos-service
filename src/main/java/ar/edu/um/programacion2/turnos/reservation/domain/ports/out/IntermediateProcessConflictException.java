package ar.edu.um.programacion2.turnos.reservation.domain.ports.out;

/**
 * El alta de un proceso chocó con el índice único parcial: el usuario ya tiene un proceso en un estado intermedio (DPT-17).
 * Pasa cuando dos pedidos del mismo usuario se guardan a la vez (CU-04 7a): los dos pasaron la búsqueda previa y la base
 * dejó entrar a uno solo.
 * <p>
 * Es parte del contrato del puerto y no una {@code DataAccessException} sin traducir, porque el caso de uso tiene que
 * decidir con ella: busca el proceso que ganó y responde como en 3a o 4a. La transacción del alta ya no sirve (quedó
 * marcada para deshacerse), así que esa búsqueda va en otra.
 */
public class IntermediateProcessConflictException extends RuntimeException {

    public IntermediateProcessConflictException(Throwable cause) {
        super("El usuario ya tiene un proceso de reserva en curso", cause);
    }
}
