package ar.edu.um.programacion2.turnos.reservation.application.usecases;

import java.time.Duration;
import java.util.Objects;

/**
 * Las cotas de una ejecución de CU-11, para que nunca dure más de lo previsto aunque la cátedra esté lenta:
 *
 * @param maxProcessesPerRun cuántos procesos le preguntan a la cátedra como máximo en una ejecución; el resto, a la próxima
 * @param runDeadline después de este tiempo no se empieza otro proceso; el que está en curso termina (con sus reintentos)
 */
public record ReconciliationLimits(int maxProcessesPerRun, Duration runDeadline) {
    public ReconciliationLimits {
        if (maxProcessesPerRun < 1) {
            throw new IllegalArgumentException("El tope de procesos por ejecución tiene que ser positivo");
        }
        Objects.requireNonNull(runDeadline, "Falta el plazo de la ejecución");
        if (runDeadline.isNegative() || runDeadline.isZero()) {
            throw new IllegalArgumentException("El plazo de la ejecución tiene que ser positivo");
        }
    }
}
