package ar.edu.um.programacion2.turnos.reservation.application.usecases;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Esperas entre reintentos en segundo plano (DPT-08), como el {@code RetryBackoff} del catálogo: antes del reintento
 * {@code n} se espera {@code delays[n - 1]}. Con {@code 1s, 2s, 4s} hay hasta 3 reintentos, o sea, 4 intentos.
 * <p>
 * La espera pasa por un {@link Sleeper} para que los tests verifiquen qué esperas se pidieron sin dormir de verdad. En la
 * aplicación es {@code Thread::sleep}: se corta si interrumpen el hilo (al apagar la aplicación).
 */
public record RetryBackoff(List<Duration> delays, Sleeper sleeper) {
    /** Espera bloqueando el hilo. Si interrumpen el hilo mientras espera, lanza {@link InterruptedException}. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    public RetryBackoff {
        delays = List.copyOf(Objects.requireNonNull(delays, "Faltan las esperas entre reintentos"));
        Objects.requireNonNull(sleeper, "Falta el sleeper");
        for (Duration delay : delays) {
            if (delay.isNegative()) {
                throw new IllegalArgumentException("Una espera entre reintentos no puede ser negativa: " + delay);
            }
        }
    }

    public int maxRetries() {
        return delays.size();
    }

    /** Espera lo que corresponde antes del reintento número {@code retry} (el primero es 1). */
    void pauseBeforeRetry(int retry) throws InterruptedException {
        sleeper.sleep(delays.get(retry - 1));
    }
}
