package ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.repository;

import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.entity.ProcessedEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repositorio de Spring Data de {@code processed_event}. La deduplicación la arma CU-05 (17/11). */
public interface JpaProcessedEventRepository extends JpaRepository<ProcessedEventEntity, String> {}
