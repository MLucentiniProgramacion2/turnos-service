package ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.repository;

import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.entity.ReservationProcessEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repositorio de Spring Data de {@code reservation_process}. Las consultas las suman los casos de uso (CU-04 en adelante). */
public interface JpaReservationProcessRepository extends JpaRepository<ReservationProcessEntity, UUID> {}
