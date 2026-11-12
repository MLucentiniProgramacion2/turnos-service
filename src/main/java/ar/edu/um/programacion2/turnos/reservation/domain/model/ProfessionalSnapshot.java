package ar.edu.um.programacion2.turnos.reservation.domain.model;

/**
 * Datos descriptivos del profesional tal como los informó el catálogo al iniciar la reserva (DPT-12). Son históricos y
 * solo para mostrar: turnos no guarda una réplica del catálogo y nunca los usa para decidir.
 */
public record ProfessionalSnapshot(String firstName, String lastName, String categoryName, int slotDurationMinutes) {
    public static final int MAX_LENGTH = 100;

    public ProfessionalSnapshot {
        Texts.requireText(firstName, "el nombre del profesional", MAX_LENGTH);
        Texts.requireText(lastName, "el apellido del profesional", MAX_LENGTH);
        Texts.requireText(categoryName, "la categoría del profesional", MAX_LENGTH);
        if (slotDurationMinutes <= 0) {
            throw new IllegalArgumentException("La duración del turno tiene que ser positiva");
        }
    }
}
