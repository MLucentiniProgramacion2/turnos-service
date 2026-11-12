package ar.edu.um.programacion2.turnos.reservation.domain.ports.out;

/** La respuesta no respeta el contrato: un status inesperado (incluidos los {@code 3xx}, que no se siguen), un {@code 4xx} sin {@code code}, o un cuerpo exitoso de una consulta que no se puede leer o al que le falta un dato. No se reintenta. */
public final class CatedraInvalidResponseException extends CatedraException {

    public CatedraInvalidResponseException(String message) {
        super(message);
    }

    public CatedraInvalidResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
