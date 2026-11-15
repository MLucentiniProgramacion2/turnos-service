package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.util.Optional;

/**
 * Nombre del paciente que se manda al confirmar el hold (contrato §10). Sale del perfil del usuario (DPT-02) y se guarda
 * para poder reintentar el confirm (CU-11).
 * <p>
 * Es un dato personal: {@link #toString()} lo enmascara, para que no termine entero en un log.
 */
public record PatientName(String firstName, String lastName) {
    /** Lo que admite la cátedra: no blanco, de 2 a 100 caracteres. */
    public static final int MIN_LENGTH = 2;

    public static final int MAX_LENGTH = 100;

    public PatientName {
        requireValid(firstName, "el nombre del paciente");
        requireValid(lastName, "el apellido del paciente");
    }

    /**
     * El nombre del paciente a partir de un perfil que puede no cumplir lo que pide la cátedra (un usuario viejo, o creado
     * antes de DPT-02): vacío en vez de una excepción, para que CU-04 pueda responder que el perfil está incompleto.
     */
    public static Optional<PatientName> tryOf(String firstName, String lastName) {
        return isValid(firstName) && isValid(lastName) ? Optional.of(new PatientName(firstName, lastName)) : Optional.empty();
    }

    private static boolean isValid(String value) {
        return value != null && !value.isBlank() && value.length() >= MIN_LENGTH && value.length() <= MAX_LENGTH;
    }

    private static void requireValid(String value, String name) {
        Texts.requireText(value, name, MAX_LENGTH);
        if (value.length() < MIN_LENGTH) {
            throw new IllegalArgumentException(name + " tiene que tener al menos " + MIN_LENGTH + " caracteres");
        }
    }

    @Override
    public String toString() {
        return "PatientName[firstName=" + Texts.mask(firstName) + ", lastName=" + Texts.mask(lastName) + "]";
    }
}
