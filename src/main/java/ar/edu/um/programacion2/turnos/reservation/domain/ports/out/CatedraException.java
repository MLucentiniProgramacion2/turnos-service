package ar.edu.um.programacion2.turnos.reservation.domain.ports.out;

/**
 * Falla de una llamada a la API REST de la cátedra. Es {@code sealed}: los casos de uso (CU-03, CU-04, CU-09 a CU-11)
 * deciden con un {@code switch} completo, y el compilador avisa si falta un caso.
 * <ul>
 * <li>{@link CatedraUnavailableException}: la operación <strong>no se aplicó</strong> o no tiene efectos; repetirla es
 * seguro.</li>
 * <li>{@link CatedraOutcomeUnknownException}: una operación con efectos que <strong>pudo haberse aplicado</strong>
 * (contrato §18.4: un timeout no prueba que falló). Antes de repetirla hay que reconciliar.</li>
 * <li>{@link CatedraRejectedException}: la cátedra respondió y rechazó la operación, con un {@code code}.</li>
 * <li>{@link CatedraAccessDeniedException}: la cuenta técnica no tiene acceso (configuración nuestra, DPT-18).</li>
 * <li>{@link CatedraInvalidResponseException}: la respuesta no respeta el contrato.</li>
 * </ul>
 * Los mensajes nunca llevan el token, la URL ni el {@code detail} de la cátedra: el detalle técnico va al log.
 */
public abstract sealed class CatedraException
    extends RuntimeException
    permits
        CatedraUnavailableException,
        CatedraOutcomeUnknownException,
        CatedraRejectedException,
        CatedraAccessDeniedException,
        CatedraInvalidResponseException {

    protected CatedraException(String message) {
        super(message);
    }

    protected CatedraException(String message, Throwable cause) {
        super(message, cause);
    }
}
