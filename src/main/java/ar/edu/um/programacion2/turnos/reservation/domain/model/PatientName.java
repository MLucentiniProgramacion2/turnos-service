package ar.edu.um.programacion2.turnos.reservation.domain.model;

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
