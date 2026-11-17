package ar.edu.um.programacion2.turnos.reservation.application.usecases;

import ar.edu.um.programacion2.turnos.reservation.domain.model.PageQuery;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessId;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ResultPage;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.IntermediateProcessConflictException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ReservationProcessRepository;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.entity.ReservationProcessEntity;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.mapper.ReservationProcessMapper;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * El puerto de persistencia en memoria, para los tests unitarios de los casos de uso. Hace lo que hace la base y que los
 * casos de uso necesitan ver: el índice único parcial (un intermedio por usuario), el bloqueo optimista y que cada lectura
 * devuelve una copia (como una transacción nueva). Tiene ganchos para simular lo que pasa "en el medio": otro pedido que
 * se guarda justo antes del alta, o un evento de Kafka que cambia la fila justo antes de un guardado.
 */
class InMemoryReservationProcessRepository implements ReservationProcessRepository {

    private final ReservationProcessMapper mapper = new ReservationProcessMapper();
    private final FakeTransactions transactions;
    private final Map<ReservationProcessId, ReservationProcess> rows = new LinkedHashMap<>();
    private final List<String> writes = new ArrayList<>();
    private final Deque<Runnable> beforeInsert = new ArrayDeque<>();
    private final Deque<Runnable> beforeSave = new ArrayDeque<>();
    private RuntimeException saveFailure;
    private int saveFailuresLeft;

    InMemoryReservationProcessRepository(FakeTransactions transactions) {
        this.transactions = transactions;
    }

    @Override
    public ReservationProcess insert(ReservationProcess process) {
        transactions.requireActive();
        if (process.getVersion() != null) {
            throw new IllegalArgumentException("No es un alta");
        }
        if (!beforeInsert.isEmpty()) {
            beforeInsert.poll().run();
        }
        boolean hasIntermediate = rows.values().stream().anyMatch(row -> row.getOwner().userId() == process.getOwner().userId() && row.getStatus().isIntermediate());
        if (process.getStatus().isIntermediate() && hasIntermediate) {
            throw new IntermediateProcessConflictException(null);
        }
        writes.add("insert " + process.getStatus());
        rows.put(process.getId(), copy(process, 0L));
        return copy(process, 0L);
    }

    @Override
    public ReservationProcess save(ReservationProcess process) {
        transactions.requireActive();
        if (saveFailuresLeft > 0) {
            saveFailuresLeft--;
            throw saveFailure;
        }
        if (!beforeSave.isEmpty()) {
            beforeSave.poll().run();
        }
        ReservationProcess current = rows.get(process.getId());
        if (current == null || !current.getVersion().equals(process.getVersion())) {
            throw new OptimisticLockingFailureException("La fila cambió desde que se leyó");
        }
        writes.add("save " + process.getStatus());
        rows.put(process.getId(), copy(process, process.getVersion() + 1));
        return copy(process, process.getVersion() + 1);
    }

    @Override
    public Optional<ReservationProcess> findIntermediateByUser(long userId) {
        transactions.requireActive();
        return rows.values().stream().filter(row -> row.getOwner().userId() == userId && row.getStatus().isIntermediate()).findFirst().map(this::copy);
    }

    @Override
    public Optional<ReservationProcess> findActiveBySlot(long userId, long professionalId, LocalDate date, LocalTime startTime) {
        transactions.requireActive();
        return rows
            .values()
            .stream()
            .filter(row -> row.getOwner().userId() == userId)
            .filter(row -> row.getSlot().professionalId() == professionalId && row.getSlot().date().equals(date) && row.getSlot().startTime().equals(startTime))
            .filter(row -> row.getStatus().isIntermediate() || row.getStatus() == ReservationProcessStatus.CONFIRMED)
            .sorted(Comparator.comparing((ReservationProcess row) -> !row.getStatus().isIntermediate()))
            .findFirst()
            .map(this::copy);
    }

    @Override
    public Optional<ReservationProcess> findByReservationProcessId(String reservationProcessId) {
        transactions.requireActive();
        return rows
            .values()
            .stream()
            .filter(row -> row.getHold().map(hold -> hold.reservationProcessId().equals(reservationProcessId)).orElse(false))
            .findFirst()
            .map(this::copy);
    }

    @Override
    public Optional<ReservationProcess> findByIdAndOwner(ReservationProcessId id, long userId) {
        transactions.requireActive();
        return Optional.ofNullable(rows.get(id)).filter(row -> row.getOwner().userId() == userId).map(this::copy);
    }

    @Override
    public ResultPage<ReservationProcess> findPageByOwner(long userId, Set<ReservationProcessStatus> statuses, PageQuery page) {
        transactions.requireActive();
        List<ReservationProcess> all = rows
            .values()
            .stream()
            .filter(row -> row.getOwner().userId() == userId)
            .filter(row -> statuses.isEmpty() || statuses.contains(row.getStatus()))
            .sorted(
                Comparator.comparing((ReservationProcess row) -> row.getSlot().date())
                    .thenComparing(row -> row.getSlot().startTime())
                    .reversed()
                    .thenComparing(row -> row.getId().value())
            )
            .toList();
        List<ReservationProcess> items = all.stream().skip((long) page.number() * page.size()).limit(page.size()).map(this::copy).toList();
        return new ResultPage<>(items, page.number(), page.size(), all.size());
    }

    @Override
    public List<ReservationProcess> findIntermediates() {
        transactions.requireActive();
        return rows
            .values()
            .stream()
            .filter(row -> row.getStatus().isIntermediate())
            .sorted(Comparator.comparing(ReservationProcess::getStatusChangedAt).thenComparing(row -> row.getId().value()))
            .map(this::copy)
            .toList();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Para armar y mirar el escenario (sin transacción: es el test, no el caso de uso)
    // ---------------------------------------------------------------------------------------------------------------

    /** Guarda una fila tal cual, como si ya estuviera en la base. */
    void put(ReservationProcess process) {
        rows.put(process.getId(), copy(process, process.getVersion() == null ? 0L : process.getVersion()));
    }

    /** Cambia una fila como lo haría otra transacción (el listener de Kafka): aplica el cambio y suma uno a la versión. */
    void changeFromOutside(ReservationProcessId id, UnaryOperator<ReservationProcess> change) {
        ReservationProcess current = copy(rows.get(id));
        ReservationProcess changed = change.apply(current);
        rows.put(id, copy(changed, current.getVersion() + 1));
    }

    ReservationProcess get(ReservationProcessId id) {
        return copy(rows.get(id));
    }

    List<ReservationProcess> all() {
        return rows.values().stream().map(this::copy).toList();
    }

    /** Las escrituras que hizo el caso de uso, en orden: "insert CREATING_HOLD", "save HOLD_CREATED"... */
    List<String> writes() {
        return List.copyOf(writes);
    }

    /** Lo que pasa justo antes del próximo alta, entre la búsqueda de duplicados y el {@code INSERT}. */
    void beforeNextInsert(Runnable action) {
        beforeInsert.add(action);
    }

    /** Lo que pasa justo antes del próximo guardado, entre la lectura y el {@code UPDATE}. */
    void beforeNextSave(Runnable action) {
        beforeSave.add(action);
    }

    /** Los próximos {@code times} guardados fallan con esa excepción (la base caída). */
    void failNextSaves(int times, RuntimeException failure) {
        saveFailuresLeft = times;
        saveFailure = failure;
    }

    private ReservationProcess copy(ReservationProcess process) {
        return copy(process, process.getVersion());
    }

    private ReservationProcess copy(ReservationProcess process, Long version) {
        ReservationProcessEntity entity = mapper.toEntity(process);
        entity.setVersion(version);
        return mapper.toDomain(entity);
    }
}
