package ar.edu.um.programacion2.turnos.reservation.domain.ports.out;

/** La operación no se aplicó: no se pudo conectar con la cátedra, o era una consulta sin efectos que falló (timeout, corte, {@code 408}, {@code 429} o {@code 5xx}). Repetirla es seguro. */
public final class CatedraUnavailableException extends CatedraException {

    public CatedraUnavailableException(String message) {
        super(message);
    }

    public CatedraUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
