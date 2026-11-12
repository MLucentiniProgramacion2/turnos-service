package ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catedra;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Los formatos de la cátedra (contrato §4): fechas {@code yyyy-MM-dd} e instantes ISO-8601. Las horas las lee
 * {@code LocalTimeFormat}, que comparte con el cliente del catálogo. Cada método lanza {@link IllegalArgumentException} con el nombre del campo y sin
 * el valor; el cliente la convierte en la excepción del puerto que corresponda.
 */
final class CatedraFormat {

    private CatedraFormat() {}

    static LocalDate date(String value, String field) {
        try {
            return LocalDate.parse(required(value, field));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " no es una fecha yyyy-MM-dd");
        }
    }

    static Instant instant(String value, String field) {
        try {
            return Instant.parse(required(value, field));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " no es un instante ISO-8601");
        }
    }

    static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Falta " + field);
        }
        return value;
    }

    static <T> T required(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("Falta " + field);
        }
        return value;
    }
}
