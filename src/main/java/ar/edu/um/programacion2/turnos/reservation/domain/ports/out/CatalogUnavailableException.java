package ar.edu.um.programacion2.turnos.reservation.domain.ports.out;

import java.time.Duration;
import java.util.Optional;

/**
 * El catálogo no respondió, respondió {@code 408}, {@code 429} o {@code 5xx}, o todavía no tiene datos
 * ({@code 503 CATALOG_NOT_INITIALIZED}). CU-03 y CU-04 responden {@code 503} sin guardar nada (DPT-11).
 * <p>
 * {@link #retryAfter()} es el {@code Retry-After} del catálogo, si lo mandó: cuándo vuelve a revisar si tiene datos.
 * CU-03 decide si se lo reenvía a la app.
 */
public final class CatalogUnavailableException extends CatalogException {

    private final transient Duration retryAfter;

    public CatalogUnavailableException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public CatalogUnavailableException(String message, Throwable cause) {
        super(message, cause);
        this.retryAfter = null;
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
