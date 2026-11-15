package ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.repository;

import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.entity.ReservationProcessEntity;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repositorio de Spring Data de {@code reservation_process}, con las consultas que piden los casos de uso. */
public interface JpaReservationProcessRepository extends JpaRepository<ReservationProcessEntity, UUID> {
    /** Consulta derivada: los procesos del usuario en alguno de esos estados. */
    List<ReservationProcessEntity> findByUserIdAndStatusIn(Long userId, Collection<ReservationProcessStatus> statuses);

    /** Consulta derivada: el proceso con ese id si es de ese usuario. */
    Optional<ReservationProcessEntity> findByIdAndUserId(UUID id, Long userId);

    /**
     * Los procesos del usuario para un turno (profesional, fecha y hora de inicio) en alguno de esos estados, del más
     * nuevo al más viejo. En JPQL: con siete condiciones, el nombre de una consulta derivada no se podría leer.
     * <p>
     * <strong>La hora va como texto y la convierte PostgreSQL</strong> ({@code cast(? as time)}), como en el catálogo (#27).
     * La columna guarda la hora sin convertir de zona ({@code @JdbcTypeCode(SqlTypes.LOCAL_TIME)}), pero un parámetro
     * {@code LocalTime} sí se convierte de la zona de la JVM a UTC ({@code hibernate.jdbc.time_zone: UTC}): con la JVM en
     * -03, buscar las 09:00 compararía contra las 12:00 y no encontraría nada. Medido: así falló el primer intento, en los
     * tres tests de esta búsqueda.
     */
    @Query(
        """
        select p from ReservationProcessEntity p
        where p.userId = :userId
          and p.professionalId = :professionalId
          and p.appointmentDate = :date
          and p.startTime = cast(:startTime as LocalTime)
          and p.status in :statuses
        order by p.createdAt desc
        """
    )
    List<ReservationProcessEntity> findBySlot(
        @Param("userId") Long userId,
        @Param("professionalId") Long professionalId,
        @Param("date") LocalDate date,
        @Param("startTime") String startTime,
        @Param("statuses") Collection<ReservationProcessStatus> statuses
    );
}
