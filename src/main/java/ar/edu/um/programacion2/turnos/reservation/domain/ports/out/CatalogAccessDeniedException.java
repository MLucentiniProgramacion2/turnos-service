package ar.edu.um.programacion2.turnos.reservation.domain.ports.out;

/**
 * El catálogo rechazó el token técnico de servicio ({@code 401} o {@code 403}, sin cuerpo). Es un error de configuración
 * nuestro (la clave compartida o la vida del token): va al log y al usuario se le responde un error genérico (DPT-18).
 */
public final class CatalogAccessDeniedException extends CatalogException {

    public CatalogAccessDeniedException(String message) {
        super(message);
    }
}
