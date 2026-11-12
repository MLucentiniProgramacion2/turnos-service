package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Teléfono del paciente, normalizado como lo hace la cátedra antes de validar (contrato §15.5): sin espacios, guiones,
 * paréntesis ni puntos, y con el formato {@code ^\+?[0-9]{7,15}$}.
 * <p>
 * Es un dato personal: {@link #toString()} muestra solo los dos últimos dígitos.
 */
public record PhoneNumber(String value) {
    private static final Pattern VALID = Pattern.compile("^\\+?[0-9]{7,15}$");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-().]");

    public PhoneNumber {
        Objects.requireNonNull(value, "Falta el teléfono");
        if (!VALID.matcher(value).matches()) {
            // El valor no va en el mensaje: es un dato personal.
            throw new IllegalArgumentException("El teléfono no tiene el formato que acepta la cátedra");
        }
    }

    /** Normaliza lo que escribió el usuario y lo valida. */
    public static PhoneNumber of(String raw) {
        Objects.requireNonNull(raw, "Falta el teléfono");
        return new PhoneNumber(SEPARATORS.matcher(raw).replaceAll(""));
    }

    @Override
    public String toString() {
        return "PhoneNumber[***" + value.substring(value.length() - 2) + "]";
    }
}
