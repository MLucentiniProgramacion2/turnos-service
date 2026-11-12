package ar.edu.um.programacion2.turnos.reservation.domain.ports.out;

/**
 * La respuesta del catálogo no respeta su contrato: JSON roto, un campo ausente o con formato inválido, otro profesional,
 * un {@code 404} sin {@code PROFESSIONAL_NOT_FOUND} (probablemente una URL mal configurada) o un status inesperado.
 */
public final class CatalogInvalidResponseException extends CatalogException {

    public CatalogInvalidResponseException(String message) {
        super(message);
    }

    public CatalogInvalidResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
