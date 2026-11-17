package ar.edu.um.programacion2.turnos.reservation.application.usecases;

import ar.edu.um.programacion2.turnos.reservation.application.usecases.ReservationProcessUpdater.Updated;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraHold;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraReservation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraReservationList;
import ar.edu.um.programacion2.turnos.reservation.domain.model.Closing;
import ar.edu.um.programacion2.turnos.reservation.domain.model.HoldConfirmation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.NoEffectReason;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReconciliationAction;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReconciliationPolicy;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReconciliationReport;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReconciliationReport.Ending;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.TransitionResult;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.in.ReconcilePendingProcessesUseCase;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.AppointmentGateway;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraAccessDeniedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraInvalidResponseException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraOutcomeUnknownException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraRejectedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraUnavailableException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.HoldGateway;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ReservationProcessRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * CU-11: reconcilia los procesos intermedios con lo que informa la cátedra, usando los identificadores persistidos.
 * <pre>
 * ¿otra ejecución en curso? → [intermedios, del más viejo al más nuevo] → por cada uno:
 *     política sin la cátedra → (si hace falta) GET /api/appointments con reintentos (afuera) → política con la cátedra
 *     → (si corresponde) POST del confirm con reintentos (afuera) → [releer, recalcular la decisión, aplicar, guardar]
 * </pre>
 * <ul>
 * <li><strong>Una ejecución a la vez</strong> (1a): un {@link ReentrantLock} justo con {@code tryLock(0, ...)}: si está
 * ocupado, se saltea sin esperar. {@code tryLock()} sin plazo toma el lock aunque haya otros esperando, incluso siendo
 * justo (lo midió el catálogo, #35). Supone una sola instancia del servicio: el lock vive en la memoria del proceso.</li>
 * <li><strong>La decisión es del dominio</strong> ({@link ReconciliationPolicy}); este caso de uso busca los datos, pide la
 * decisión y la aplica. <strong>La vuelve a pedir sobre el proceso releído</strong> adentro del guardado (5a): si un
 * evento o CU-09 lo movió mientras se esperaba a la cátedra, la decisión nueva puede ser "nada".</li>
 * <li><strong>Ningún pedido HTTP adentro de una transacción</strong> ({@code Propagation.NEVER}).</li>
 * <li><strong>Reintentos en segundo plano</strong> (DPT-08, 4a): hasta 3 con 1, 2 y 4 s cuando la cátedra no está
 * disponible. El confirm se repite solo si no se llegó a conectar, y nunca después de {@code expiresAt}; un resultado
 * desconocido queda para la próxima ejecución, que vuelve a listar antes (§18.4). El {@code POST} del hold no se hace
 * nunca (DPT-06).</li>
 * <li><strong>Cortes:</strong> la credencial técnica rechazada (4b) o un proceso que agotó los reintentos porque la cátedra
 * no responde (4c) cortan la ejecución: los demás fallarían igual. Además, hasta {@code maxProcessesPerRun} procesos le
 * preguntan a la cátedra, y pasado {@code runDeadline} no se empieza otro: el resto, a la próxima.</li>
 * <li><strong>Al apagar:</strong> la espera del backoff se interrumpe y la ejecución termina entre un proceso y el
 * siguiente.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class ReconcilePendingProcessesUseCaseImpl implements ReconcilePendingProcessesUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(ReconcilePendingProcessesUseCaseImpl.class);
    private static final String HOLD_EXPIRED = "HOLD_EXPIRED";

    private final ReservationProcessRepository repository;
    private final ReadTransactions readTransactions;
    private final AppointmentGateway appointments;
    private final HoldGateway holds;
    private final ReservationProcessUpdater updater;
    private final ReconciliationPolicy policy;
    private final ReconciliationLimits limits;
    private final RetryBackoff backoff;
    private final Clock clock;
    private final ReentrantLock running = new ReentrantLock(true);

    /** Lo que se cuenta en una ejecución, para el resumen. */
    private static final class Counters {

        int reviewed;
        int changed;
        int waiting;
        int errors;
    }

    /** Una falla que corta la ejecución entera (4b, 4c), o una interrupción. */
    private static final class StopRun extends Exception {

        private final transient Ending ending;

        StopRun(Ending ending) {
            super(ending.name(), null, false, false);
            this.ending = ending;
        }
    }

    @Override
    public ReconciliationReport reconcile() {
        boolean acquired;
        try {
            acquired = running.tryLock(0, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ReconciliationReport.Skipped();
        }
        if (!acquired) {
            LOG.debug("CU-11: ya hay una reconciliación en curso; esta se saltea");
            return new ReconciliationReport.Skipped();
        }
        try {
            return run();
        } finally {
            running.unlock();
        }
    }

    private ReconciliationReport run() {
        Instant start = clock.instant();
        List<ReservationProcess> pending = readTransactions.template().execute(status -> repository.findIntermediates());
        Counters counters = new Counters();
        int askedCatedra = 0;
        Ending ending = Ending.FINISHED;
        for (ReservationProcess process : pending) {
            if (Thread.currentThread().isInterrupted()) {
                ending = Ending.INTERRUPTED;
                break;
            }
            if (Duration.between(start, clock.instant()).compareTo(limits.runDeadline()) > 0) {
                ending = Ending.DEADLINE;
                break;
            }
            Optional<ReconciliationAction> withoutCatedra = policy.needsCatedra(process, clock.instant());
            if (withoutCatedra.isPresent() && withoutCatedra.orElseThrow() instanceof ReconciliationAction.Wait) {
                // Todavía no corresponde (DPT-24, DPT-09): no cuenta como revisado.
                continue;
            }
            if (withoutCatedra.isEmpty() && askedCatedra >= limits.maxProcessesPerRun()) {
                ending = Ending.MAX_PROCESSES;
                break;
            }
            counters.reviewed++;
            try {
                if (withoutCatedra.isPresent()) {
                    act(process, withoutCatedra.orElseThrow(), current -> policy.needsCatedra(current, clock.instant()), counters);
                } else {
                    askedCatedra++;
                    reconcileWithCatedra(process, counters);
                }
            } catch (StopRun stop) {
                ending = stop.ending;
                break;
            }
        }
        report(pending.size(), counters, ending);
        return new ReconciliationReport.Completed(counters.reviewed, counters.changed, counters.waiting, counters.errors, ending);
    }

    private void reconcileWithCatedra(ReservationProcess process, Counters counters) throws StopRun {
        CatedraHold hold = process.getHold().orElseThrow();
        Optional<CatedraReservationList> listed = list(process, counters);
        if (listed.isEmpty()) {
            return;
        }
        CatedraReservationList list = listed.orElseThrow();
        Optional<CatedraReservation> found = list.find(hold.reservationProcessId());
        String externalPatientId = process.getOwner().externalPatientId();
        if (found.isPresent() && found.orElseThrow().externalPatientId() != null && !externalPatientId.equals(found.orElseThrow().externalPatientId())) {
            LOG.warn("CU-11: la reserva {} de la cátedra no es del dueño del proceso {}: no se reconcilia", hold.reservationProcessId(), process.getId());
            counters.errors++;
            return;
        }
        boolean provesAbsence = list.provesAbsence();
        ReconciliationAction action = policy.decide(process, found, provesAbsence, clock.instant());
        if (action instanceof ReconciliationAction.RetryConfirm) {
            retryConfirm(process, counters);
        } else {
            act(process, action, current -> Optional.of(policy.decide(current, found, provesAbsence, clock.instant())), counters);
        }
    }

    /**
     * Aplica una decisión. El hecho se recalcula sobre el proceso releído adentro del guardado (5a): si ya no corresponde,
     * no se hace nada.
     */
    private void act(
        ReservationProcess process,
        ReconciliationAction action,
        Function<ReservationProcess, Optional<ReconciliationAction>> recompute,
        Counters counters
    ) {
        switch (action) {
            case ReconciliationAction.Wait wait -> {
                counters.waiting++;
                LOG.debug("CU-11: el proceso {} sigue esperando ({})", process.getId(), wait.reason());
            }
            case ReconciliationAction.RetryConfirm retry -> throw new IllegalStateException("El reintento del confirm no se aplica acá");
            case ReconciliationAction.Apply apply -> {
                String[] reason = { apply.reason() };
                save(
                    process,
                    current ->
                        recompute
                            .apply(current)
                            .filter(ReconciliationAction.Apply.class::isInstance)
                            .map(ReconciliationAction.Apply.class::cast)
                            .map(again -> {
                                reason[0] = again.reason();
                                return again.fact().applyTo(current, clock.instant());
                            })
                            .orElseGet(() -> new TransitionResult.NoEffect(current.getStatus(), NoEffectReason.NOT_APPLICABLE)),
                    reason,
                    counters
                );
            }
        }
    }

    /** Releer, aplicar y guardar, y contar y loguear lo que pasó. */
    private void save(ReservationProcess process, Function<ReservationProcess, TransitionResult> transition, String[] reason, Counters counters) {
        Updated updated;
        try {
            updated = updater.apply(process.getId(), process.getOwner().userId(), transition);
        } catch (RuntimeException e) {
            counters.errors++;
            LOG.warn("CU-11: no se pudo guardar el proceso {}; queda para la próxima ejecución", process.getId(), e);
            return;
        }
        switch (updated.result()) {
            case TransitionResult.Applied applied -> {
                counters.changed++;
                if (ReconciliationPolicy.PHONE_PENDING_TIMEOUT.equals(reason[0])) {
                    LOG.warn(
                        "CU-11: proceso {} {} -> {} ({}): la cátedra lo sigue teniendo PHONE_PENDING pasada la espera máxima; reservationProcessId={}",
                        process.getId(),
                        applied.from(),
                        applied.to(),
                        reason[0],
                        process.getHold().map(CatedraHold::reservationProcessId).orElse(null)
                    );
                } else {
                    LOG.info("CU-11: proceso {} {} -> {} ({})", process.getId(), applied.from(), applied.to(), reason[0]);
                }
            }
            case TransitionResult.NoEffect noEffect -> {
                // 5a: un evento o CU-09 lo movió mientras tanto, y la decisión ya no corresponde.
                counters.waiting++;
                LOG.debug("CU-11: el proceso {} cambió mientras se reconciliaba ({}): no se hace nada", process.getId(), noEffect.state());
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Llamadas a la cátedra, con reintentos (DPT-08)
    // ---------------------------------------------------------------------------------------------------------------

    /** La lista del dueño para la fecha del turno. Vacío si falló para este proceso (queda para la próxima). */
    private Optional<CatedraReservationList> list(ReservationProcess process, Counters counters) throws StopRun {
        String externalPatientId = process.getOwner().externalPatientId();
        for (int attempt = 1; ; attempt++) {
            try {
                return Optional.of(appointments.listReservations(externalPatientId, process.getSlot().date(), process.getSlot().date()));
            } catch (CatedraUnavailableException e) {
                retryOrStop(process, attempt, "listar las reservas");
            } catch (CatedraAccessDeniedException e) {
                LOG.error("CU-11: la cátedra rechazó la credencial técnica: {}. Se corta la ejecución", e.getMessage());
                throw new StopRun(Ending.ACCESS_DENIED);
            } catch (CatedraException e) {
                // Rechazado, fuera de contrato o (no debería pasar en un GET) resultado desconocido: es nuestro o de la cátedra,
                // y repetirlo daría lo mismo.
                LOG.error("CU-11: la lista de la cátedra para el proceso {} falló ({}): queda para la próxima", process.getId(), e.getMessage());
                counters.errors++;
                return Optional.empty();
            }
        }
    }

    /**
     * 3a: el confirm no se aplicó (una lista completa no lo tiene) y no pasó {@code expiresAt}. Se reintenta con los datos
     * guardados: el paciente del proceso y el {@code externalPatientId} del dueño.
     */
    private void retryConfirm(ReservationProcess process, Counters counters) throws StopRun {
        CatedraHold hold = process.getHold().orElseThrow();
        for (int attempt = 1; ; attempt++) {
            if (!clock.instant().isBefore(hold.expiresAt())) {
                // DPT-08: nunca después de expiresAt. Lo cierra una ejecución posterior, pasado el margen.
                counters.waiting++;
                LOG.info("CU-11: el confirm del proceso {} no se reintenta: pasó expiresAt", process.getId());
                return;
            }
            HoldConfirmation confirmation;
            try {
                confirmation = holds.confirmHold(hold.holdId(), process.getOwner().externalPatientId(), process.getPatient());
            } catch (CatedraUnavailableException e) {
                // No se llegó a conectar: seguro que no se aplicó, repetir es seguro.
                retryOrStop(process, attempt, "reintentar el confirm");
                continue;
            } catch (CatedraOutcomeUnknownException e) {
                // Pudo haberse aplicado: no se repite a ciegas. La próxima ejecución vuelve a listar antes (§18.4).
                counters.errors++;
                LOG.warn("CU-11: resultado desconocido al reintentar el confirm del proceso {}: queda para la próxima", process.getId());
                return;
            } catch (CatedraRejectedException e) {
                confirmRejected(process, e, counters);
                return;
            } catch (CatedraAccessDeniedException e) {
                LOG.error("CU-11: la cátedra rechazó la credencial técnica al confirmar: {}. Se corta la ejecución", e.getMessage());
                throw new StopRun(Ending.ACCESS_DENIED);
            } catch (CatedraInvalidResponseException e) {
                counters.errors++;
                LOG.error("CU-11: la cátedra respondió fuera de su contrato al confirmar el proceso {}: {}", process.getId(), e.getMessage());
                return;
            }
            save(process, current -> current.confirmAccepted(confirmation.expiresAt(), clock.instant()), new String[] { "CONFIRM_RETRIED" }, counters);
            return;
        }
    }

    private void confirmRejected(ReservationProcess process, CatedraRejectedException rejected, Counters counters) {
        switch (rejected.code()) {
            // Ya estaba confirmado: es un éxito (DPT-07).
            case RESERVATION_PROCESS_ALREADY_CONFIRMED -> save(
                process,
                current -> current.confirmAccepted(null, clock.instant()),
                new String[] { "CONFIRM_ALREADY_APPLIED" },
                counters
            );
            // §18.3.
            case HOLD_EXPIRED -> save(process, current -> current.expired(new Closing(HOLD_EXPIRED, null), clock.instant()), new String[] { HOLD_EXPIRED }, counters);
            default -> {
                LOG.error("CU-11: la cátedra rechazó el confirm del proceso {} con {} {}: pasa a FAILED", process.getId(), rejected.status(), rejected.code());
                String code = rejected.code().name();
                save(process, current -> current.failed(new Closing(code, null), clock.instant()), new String[] { code }, counters);
            }
        }
    }

    /** Espera antes del reintento {@code attempt}, o corta la ejecución si se agotaron (4c) o si interrumpieron el hilo. */
    private void retryOrStop(ReservationProcess process, int attempt, String operation) throws StopRun {
        if (attempt > backoff.maxRetries()) {
            LOG.error("CU-11: la cátedra no respondió al {} del proceso {} después de {} intentos. Se corta la ejecución", operation, process.getId(), attempt);
            throw new StopRun(Ending.CATEDRA_UNAVAILABLE);
        }
        LOG.warn("CU-11: la cátedra no respondió al {} del proceso {} (intento {}): se reintenta", operation, process.getId(), attempt);
        try {
            backoff.pauseBeforeRetry(attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StopRun(Ending.INTERRUPTED);
        }
    }

    /** El resumen: INFO si pasó algo; DEBUG si no, para no llenar el log cada 60 s. */
    private static void report(int pending, Counters counters, Ending ending) {
        boolean somethingHappened = counters.changed > 0 || counters.errors > 0 || ending != Ending.FINISHED;
        String format = "CU-11: {} intermedios, {} revisados, {} cambiados, {} esperando, {} con error ({})";
        Object[] values = { pending, counters.reviewed, counters.changed, counters.waiting, counters.errors, ending };
        if (somethingHappened) {
            LOG.info(format, values);
        } else {
            LOG.debug(format, values);
        }
    }
}
