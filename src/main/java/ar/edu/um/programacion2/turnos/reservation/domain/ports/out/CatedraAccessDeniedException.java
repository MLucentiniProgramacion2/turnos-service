package ar.edu.um.programacion2.turnos.reservation.domain.ports.out;

/** La cátedra rechazó la credencial técnica: {@code 401}, {@code 403} sin {@code code} (Spring Security, contrato §13) o {@code 403 STUDENT_INTEGRATION_NOT_FOUND}. Es un error de configuración nuestro: va al log y no se le muestra al usuario (DPT-18). No se reintenta. */
public final class CatedraAccessDeniedException extends CatedraException {

    public CatedraAccessDeniedException(String message) {
        super(message);
    }

    public CatedraAccessDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}
