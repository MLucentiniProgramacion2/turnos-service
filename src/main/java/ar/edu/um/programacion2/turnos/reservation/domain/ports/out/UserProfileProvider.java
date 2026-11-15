package ar.edu.um.programacion2.turnos.reservation.domain.ports.out;

import ar.edu.um.programacion2.turnos.reservation.domain.model.UserProfile;
import java.util.Optional;

/** El perfil del usuario dueño de una reserva (CU-04). Solo lectura: los usuarios los administra JHipster. */
public interface UserProfileProvider {
    /** Vacío si el usuario ya no existe (el token sigue siendo válido hasta que vence). Exige una transacción abierta. */
    Optional<UserProfile> findById(long userId);
}
