package ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.repository;

import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.entity.UserProfileEntity;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Repositorio de solo lectura del perfil. Extiende {@link Repository}, la interfaz vacía de Spring Data, y no
 * {@code JpaRepository}: así no hereda {@code save} ni {@code delete}, y lo único que existe es la búsqueda por id.
 */
public interface JpaUserProfileRepository extends Repository<UserProfileEntity, Long> {
    Optional<UserProfileEntity> findById(Long id);
}
