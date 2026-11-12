package ar.edu.um.programacion2.turnos.reservation.domain.model;

/**
 * Dueño del proceso (DPT-21): el {@code id} interno del usuario, que es lo que autoriza. El login se guarda solo para
 * mostrar y para la trazabilidad, porque un admin lo puede cambiar.
 */
public record ProcessOwner(long userId, String login) {
    /** El mismo máximo que el login de JHipster (DPT-20). */
    public static final int MAX_LOGIN_LENGTH = 50;

    public ProcessOwner {
        if (userId <= 0) {
            throw new IllegalArgumentException("El id del usuario tiene que ser positivo");
        }
        Texts.requireText(login, "el login", MAX_LOGIN_LENGTH);
    }

    /** {@code externalPatientId} que se manda a la cátedra (DPT-01): identifica al usuario, pero nunca autoriza. */
    public String externalPatientId() {
        return "user-" + userId;
    }
}
