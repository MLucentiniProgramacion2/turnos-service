package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

/**
 * Las reglas de DPT-10 sobre qué fechas se pueden consultar y reservar. Son del dominio y no de Bean Validation porque
 * dependen de "hoy", que sale del reloj del caso de uso y de la zona horaria de atención.
 * <p>
 * Todos los límites son <strong>inclusivos</strong>:
 * <ul>
 * <li>una consulta abarca como máximo {@value #MAX_RANGE_DAYS} días: {@code to <= from + 6};</li>
 * <li>no se consulta el pasado: {@code from >= hoy};</li>
 * <li>el horizonte: {@code to <= hoy + horizonDays} (60 por defecto: el día 60 se puede consultar, el 61 no).</li>
 * </ul>
 * El máximo de 7 días no es configurable: es lo que se resuelve con una sola llamada de ocupaciones a la cátedra y lo que
 * muestra una pantalla de la app.
 *
 * @param zone        la zona horaria de atención: las fechas y horas de los turnos son locales de esa zona (contrato §4)
 * @param horizonDays cuántos días hacia adelante se ofrecen turnos
 */
public record AvailabilityPolicy(ZoneId zone, int horizonDays) {
    public static final int MAX_RANGE_DAYS = 7;

    public AvailabilityPolicy {
        Objects.requireNonNull(zone, "Falta la zona horaria de atención");
        if (horizonDays < 0) {
            throw new IllegalArgumentException("El horizonte no puede ser negativo");
        }
    }

    /** La fecha y la hora de ese instante en la zona de atención: "hoy" y "ahora" para comparar con los turnos. */
    public LocalDateTime localDateTime(Instant now) {
        return LocalDateTime.ofInstant(Objects.requireNonNull(now, "Falta el instante actual"), zone);
    }

    /** La primera regla que no cumple el rango, o vacío si se puede consultar. */
    public Optional<RangeViolation> checkRange(LocalDate from, LocalDate to, LocalDate today) {
        Objects.requireNonNull(from, "Falta la fecha desde");
        Objects.requireNonNull(to, "Falta la fecha hasta");
        Objects.requireNonNull(today, "Falta la fecha de hoy");
        if (from.isAfter(to)) {
            return Optional.of(RangeViolation.FROM_AFTER_TO);
        }
        if (to.isAfter(from.plusDays(MAX_RANGE_DAYS - 1L))) {
            return Optional.of(RangeViolation.TOO_LONG);
        }
        if (from.isBefore(today)) {
            return Optional.of(RangeViolation.IN_THE_PAST);
        }
        if (to.isAfter(lastBookableDate(today))) {
            return Optional.of(RangeViolation.BEYOND_HORIZON);
        }
        return Optional.empty();
    }

    /** Si se puede reservar un turno de esa fecha: ni pasada ni más allá del horizonte (CU-04 paso 2). */
    public boolean isBookableDate(LocalDate date, LocalDate today) {
        return checkRange(date, date, today).isEmpty();
    }

    private LocalDate lastBookableDate(LocalDate today) {
        return today.plusDays(horizonDays);
    }
}
