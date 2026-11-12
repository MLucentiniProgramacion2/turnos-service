package ar.edu.um.programacion2.turnos.reservation.infrastructure.client;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/**
 * El formato de las horas locales de atención que comparten los clientes de la cátedra y del catálogo (contrato §4: los
 * clientes aceptan {@code HH:mm} y {@code HH:mm:ss}). Uno solo, para que las dos reglas no se separen.
 * <p>
 * Es estricto: ni {@code 9:00}, ni {@code 24:00}, ni {@code 09:60}, ni fracciones de segundo, ni zona. Los errores nombran
 * el campo y nunca repiten el valor recibido.
 */
public final class LocalTimeFormat {

    private static final DateTimeFormatter PARSER = DateTimeFormatter.ofPattern("HH:mm[:ss]").withResolverStyle(ResolverStyle.STRICT);

    private LocalTimeFormat() {}

    /**
     * @param field el nombre del campo para el mensaje, por ejemplo {@code weeklySchedules.startTime}
     * @throws IllegalArgumentException si falta o no es {@code HH:mm} ni {@code HH:mm:ss}
     */
    public static LocalTime parse(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Falta " + field);
        }
        try {
            return LocalTime.parse(value, PARSER);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " no es una hora HH:mm o HH:mm:ss");
        }
    }

    /** La hora para mandar: {@code HH:mm}, o {@code HH:mm:ss} si tiene segundos (un horario puede tenerlos). */
    public static String format(LocalTime time) {
        LocalTime seconds = time.withNano(0);
        return seconds.getSecond() == 0
            ? "%02d:%02d".formatted(seconds.getHour(), seconds.getMinute())
            : "%02d:%02d:%02d".formatted(seconds.getHour(), seconds.getMinute(), seconds.getSecond());
    }
}
