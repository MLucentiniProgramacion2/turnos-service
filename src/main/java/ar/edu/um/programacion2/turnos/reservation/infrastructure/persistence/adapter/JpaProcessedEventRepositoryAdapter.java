package ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.adapter;

import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ProcessedEventRepository;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.repository.JpaProcessedEventRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adaptador de salida: la barrera de idempotencia en {@code processed_event}. {@code MANDATORY}: el {@code eventId} se
 * confirma en la misma transacción que el cambio de estado, que abre el caso de uso.
 * <p>
 * No hay mapper a {@code ProcessedEventEntity}: el adaptador nunca lee la entidad y el {@code INSERT} recibe los valores
 * sueltos. La entidad queda para que Hibernate valide la tabla y para el HQL.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
@RequiredArgsConstructor
public class JpaProcessedEventRepositoryAdapter implements ProcessedEventRepository {

    private final JpaProcessedEventRepository jpaRepository;

    @Override
    public boolean registerIfAbsent(String eventId, String eventType, Instant processedAt) {
        return jpaRepository.insertIfAbsent(eventId, eventType, processedAt) == 1;
    }
}
