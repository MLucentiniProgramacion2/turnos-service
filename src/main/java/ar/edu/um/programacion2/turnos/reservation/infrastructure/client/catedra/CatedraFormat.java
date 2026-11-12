package ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catedra;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/**
 * Los formatos de la cátedra (contrato §4): fechas {@code yyyy-MM-dd}, instantes ISO-8601 y horas locales que pueden venir
 * como {@code HH:mm} o {@code HH:mm:ss}. Cada método lanza {@link IllegalArgumentException} con el nombre del campo y sin
 * el valor; el cliente la convierte en la excepción del puerto que corresponda.
 */
final class CatedraFormat {

    /** {@code HH:mm} o {@code HH:mm:ss}, estricto: ni {@code 9:00}, ni {@code 24:00}, ni fracciones de segundo. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm[:ss]").withResolverStyle(ResolverStyle.STRICT);

    private CatedraFormat() {}

    static LocalDate date(String value, String field) {
        try {
            return LocalDate.parse(required(value, field));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " no es una fecha yyyy-MM-dd");
        }
    }

    static LocalTime time(String value, String field) {
        try {
            return LocalTime.parse(required(value, field), TIME);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " no es una hora HH:mm o HH:mm:ss");
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

    /** La hora para mandar: {@code HH:mm}, o {@code HH:mm:ss} si tiene segundos (un horario puede tenerlos). */
    static String format(LocalTime time) {
        return time.getSecond() == 0 && time.getNano() == 0 ? "%02d:%02d".formatted(time.getHour(), time.getMinute()) : time.withNano(0).toString();
    }
}
