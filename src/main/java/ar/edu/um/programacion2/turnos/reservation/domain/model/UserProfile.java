package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.util.Optional;

/**
 * Lo que la reserva necesita del usuario: su login actual y el nombre con que figura como paciente (DPT-02). Sale de la
 * tabla de usuarios, no del token: el nombre no viaja en el JWT y un admin puede haber cambiado el login.
 * <p>
 * No valida los nombres: son datos de otra parte del sistema. Si no sirven como nombre de paciente,
 * {@link #patientName()} da vacío. {@link #toString()} no los muestra: son datos personales.
 */
public record UserProfile(long userId, String login, String firstName, String lastName) {
    /** El dueño de un proceso nuevo: el id del token con el login vigente. */
    public ProcessOwner owner() {
        return new ProcessOwner(userId, login);
    }

    /** El nombre del paciente, si el perfil cumple lo que pide la cátedra al confirmar (contrato §10). */
    public Optional<PatientName> patientName() {
        return PatientName.tryOf(firstName, lastName);
    }

    @Override
    public String toString() {
        return "UserProfile[userId=" + userId + "]";
    }
}
