package ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.adapter;

import ar.edu.um.programacion2.turnos.reservation.domain.model.UserProfile;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.UserProfileProvider;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.repository.JpaUserProfileRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Adaptador de salida: lee el perfil del usuario de {@code jhi_user}. {@code MANDATORY}, como los demás adaptadores. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
@RequiredArgsConstructor
public class JpaUserProfileAdapter implements UserProfileProvider {

    private final JpaUserProfileRepository jpaRepository;

    @Override
    public Optional<UserProfile> findById(long userId) {
        return jpaRepository
            .findById(userId)
            .map(entity -> new UserProfile(entity.getId(), entity.getLogin(), entity.getFirstName(), entity.getLastName()));
    }
}
