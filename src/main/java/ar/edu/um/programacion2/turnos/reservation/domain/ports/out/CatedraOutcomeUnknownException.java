package ar.edu.um.programacion2.turnos.reservation.domain.ports.out;

/** Resultado desconocido de una operación con efectos (crear o confirmar un hold): la conexión se estableció y después hubo un timeout, un corte, un {@code 408}, {@code 429} o {@code 5xx}, o una respuesta exitosa ilegible. La operación pudo haberse aplicado: no se repite sin reconciliar antes (DPT-06, DPT-07, contrato §18.4). */
public final class CatedraOutcomeUnknownException extends CatedraException {

    public CatedraOutcomeUnknownException(String message) {
        super(message);
    }

    public CatedraOutcomeUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}
