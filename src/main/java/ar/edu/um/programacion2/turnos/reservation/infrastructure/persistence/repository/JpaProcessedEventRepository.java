package ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.repository;

import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.entity.ProcessedEventEntity;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repositorio de Spring Data de {@code processed_event}. */
public interface JpaProcessedEventRepository extends JpaRepository<ProcessedEventEntity, String> {
    /**
     * Inserta la fila si el {@code event_id} no existe, en una sola sentencia, como el catálogo (#31). {@code save} no
     * sirve: hace un {@code merge} (lee y después inserta), y entre esos dos pasos otra transacción puede insertar la misma
     * clave, con una violación que deshace la transacción entera, cambio de estado incluido.
     * <p>
     * Es HQL: {@code insert} y {@code on conflict} son de Hibernate, no de JPQL. Hibernate valida los nombres al crear el
     * repositorio, así que uno mal escrito impide arrancar; con SQL nativo, recién fallaría con el primer evento.
     *
     * @return filas insertadas: 0 si ya estaba
     */
    @Modifying
    @Query(
        """
        insert into ProcessedEventEntity (eventId, eventType, processedAt)
        values (:eventId, :eventType, :processedAt)
        on conflict(eventId) do nothing
        """
    )
    int insertIfAbsent(@Param("eventId") String eventId, @Param("eventType") String eventType, @Param("processedAt") Instant processedAt);
}
