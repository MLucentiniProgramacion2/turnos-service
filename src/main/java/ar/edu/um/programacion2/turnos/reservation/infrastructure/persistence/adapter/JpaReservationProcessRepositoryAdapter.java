package ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.adapter;

import ar.edu.um.programacion2.turnos.reservation.domain.model.PageQuery;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessId;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ResultPage;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.IntermediateProcessConflictException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ReservationProcessRepository;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.entity.ReservationProcessEntity;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.mapper.ReservationProcessMapper;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.repository.JpaReservationProcessRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adaptador de salida: los procesos de reserva en {@code reservation_process}.
 * <p>
 * {@code MANDATORY}: no abre transacciones, exige que el caso de uso ya tenga una (como los adaptadores del catálogo,
 * #21). Así no puede quedar una escritura suelta fuera de la unidad de trabajo que decidió el caso de uso, y llamarlo sin
 * transacción falla en el momento.
 * <p>
 * Las escrituras hacen {@code flush}: el {@code INSERT} o el {@code UPDATE} salen antes de volver, y una violación del
 * índice o un choque de versión se ven acá, no en el commit, donde ya no se sabría qué operación falló.
 * <p>
 * <strong>Alta y modificación son operaciones distintas.</strong> El id lo asigna el caso de uso, así que Spring Data no
 * puede distinguirlas por el id: usa la versión. En {@link #insert} la entidad va con la versión en {@code null} y Spring
 * Data hace {@code persist} (un {@code INSERT} directo); en {@link #save} va con la versión leída y hace {@code merge}, que
 * termina en un {@code UPDATE ... where version = ?}.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
@RequiredArgsConstructor
public class JpaReservationProcessRepositoryAdapter implements ReservationProcessRepository {

    /** El índice único parcial de DPT-17 (changelog {@code reservation/001-reservation-process.xml}). */
    static final String ONE_INTERMEDIATE_PER_USER = "uq_reservation_process_one_intermediate_per_user";

    private static final Set<ReservationProcessStatus> INTERMEDIATE = ReservationProcessStatus.intermediates();
    private static final Set<ReservationProcessStatus> INTERMEDIATE_OR_CONFIRMED = intermediateOrConfirmed();

    private final JpaReservationProcessRepository jpaRepository;
    private final ReservationProcessMapper mapper;

    @Override
    public ReservationProcess insert(ReservationProcess process) {
        if (process.getVersion() != null) {
            throw new IllegalArgumentException("El proceso " + process.getId() + " ya está guardado: no es un alta");
        }
        try {
            return mapper.toDomain(jpaRepository.saveAndFlush(mapper.toEntity(process)));
        } catch (DataIntegrityViolationException e) {
            // Solo esta violación es parte del contrato; cualquier otra (la FK del usuario, un CHECK) sube sin traducir.
            if (e.getCause() instanceof ConstraintViolationException violation && ONE_INTERMEDIATE_PER_USER.equals(violation.getConstraintName())) {
                throw new IntermediateProcessConflictException(e);
            }
            throw e;
        }
    }

    @Override
    public ReservationProcess save(ReservationProcess process) {
        if (process.getVersion() == null) {
            throw new IllegalArgumentException("El proceso " + process.getId() + " todavía no está guardado: es un alta");
        }
        return mapper.toDomain(jpaRepository.saveAndFlush(mapper.toEntity(process)));
    }

    @Override
    public Optional<ReservationProcess> findIntermediateByUser(long userId) {
        return jpaRepository.findByUserIdAndStatusIn(userId, INTERMEDIATE).stream().findFirst().map(mapper::toDomain);
    }

    @Override
    public Optional<ReservationProcess> findActiveBySlot(long userId, long professionalId, LocalDate date, LocalTime startTime) {
        List<ReservationProcessEntity> found = jpaRepository.findBySlot(
            userId,
            professionalId,
            date,
            startTime.toString(),
            INTERMEDIATE_OR_CONFIRMED
        );
        // Ya vienen del más nuevo al más viejo; el orden es estable, así que deja primero al intermedio, si hay uno.
        return found
            .stream()
            .sorted(Comparator.comparing((ReservationProcessEntity entity) -> !entity.getStatus().isIntermediate()))
            .findFirst()
            .map(mapper::toDomain);
    }

    @Override
    public Optional<ReservationProcess> findByReservationProcessId(String reservationProcessId) {
        return jpaRepository.findByReservationProcessId(reservationProcessId).map(mapper::toDomain);
    }

    @Override
    public Optional<ReservationProcess> findByIdAndOwner(ReservationProcessId id, long userId) {
        return jpaRepository.findByIdAndUserId(id.value(), userId).map(mapper::toDomain);
    }

    /** Orden total de "mis reservas": el turno más lejano primero, como la cátedra (§11); el {@code id} desempata. */
    static final Sort OWNER_PAGE_ORDER = Sort.by(Sort.Order.desc("appointmentDate"), Sort.Order.desc("startTime"), Sort.Order.asc("id"));

    @Override
    public ResultPage<ReservationProcess> findPageByOwner(long userId, Set<ReservationProcessStatus> statuses, PageQuery page) {
        // Sin filtro es "todos": se pasa la lista completa para que la consulta tenga una sola forma.
        Set<ReservationProcessStatus> filter = statuses.isEmpty() ? EnumSet.allOf(ReservationProcessStatus.class) : statuses;
        Page<ReservationProcessEntity> found = jpaRepository.findByUserIdAndStatusIn(userId, filter, PageRequest.of(page.number(), page.size(), OWNER_PAGE_ORDER));
        return new ResultPage<>(found.getContent().stream().map(mapper::toDomain).toList(), page.number(), page.size(), found.getTotalElements());
    }

    @Override
    public List<ReservationProcess> findIntermediates() {
        return jpaRepository.findByStatusInOrderByStatusChangedAtAscIdAsc(INTERMEDIATE).stream().map(mapper::toDomain).toList();
    }

    private static Set<ReservationProcessStatus> intermediateOrConfirmed() {
        Set<ReservationProcessStatus> statuses = EnumSet.copyOf(ReservationProcessStatus.intermediates());
        statuses.add(ReservationProcessStatus.CONFIRMED);
        return statuses;
    }
}
