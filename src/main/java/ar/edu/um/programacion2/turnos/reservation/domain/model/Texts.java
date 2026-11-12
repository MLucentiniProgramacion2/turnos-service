package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.util.Objects;

/** Validaciones de texto que comparten los valores del modelo. Lanzan {@link IllegalArgumentException}. */
final class Texts {

    private Texts() {}

    static String requireText(String value, String name, int maxLength) {
        Objects.requireNonNull(value, "Falta " + name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " no puede estar en blanco");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(name + " supera los " + maxLength + " caracteres");
        }
        return value;
    }

    /** Texto que viene de la cátedra: el contrato no le fija largo, así que solo se exige que no esté en blanco. */
    static String requireText(String value, String name) {
        return requireText(value, name, Integer.MAX_VALUE);
    }

    /** Como {@link #requireText}, pero {@code null} es válido (dato opcional). */
    static String optionalText(String value, String name, int maxLength) {
        return value == null ? null : requireText(value, name, maxLength);
    }

    static String optionalText(String value, String name) {
        return optionalText(value, name, Integer.MAX_VALUE);
    }

    /** Primera letra y asteriscos: alcanza para reconocer el dato en un log sin exponerlo. */
    static String mask(String value) {
        return value.isEmpty() ? "***" : value.charAt(0) + "***";
    }
}
