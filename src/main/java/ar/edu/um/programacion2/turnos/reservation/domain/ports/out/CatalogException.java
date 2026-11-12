package ar.edu.um.programacion2.turnos.reservation.domain.ports.out;

/**
 * Falla de una llamada al servicio de catálogo. Es {@code sealed} para que CU-03 y CU-04 decidan con un {@code switch}
 * completo. Los mensajes nunca llevan el token ni la URL.
 */
public abstract sealed class CatalogException
    extends RuntimeException
    permits CatalogUnavailableException, CatalogAccessDeniedException, CatalogInvalidResponseException {

    protected CatalogException(String message) {
        super(message);
    }

    protected CatalogException(String message, Throwable cause) {
        super(message, cause);
    }
}
