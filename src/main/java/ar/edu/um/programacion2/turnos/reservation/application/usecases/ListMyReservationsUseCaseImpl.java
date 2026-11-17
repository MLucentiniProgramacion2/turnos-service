package ar.edu.um.programacion2.turnos.reservation.application.usecases;

import ar.edu.um.programacion2.turnos.reservation.application.usecases.ReservationProcessUpdater.Updated;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraHold;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraReservation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraReservationList;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ConvergenceFact;
import ar.edu.um.programacion2.turnos.reservation.domain.model.MyReservations;
import ar.edu.um.programacion2.turnos.reservation.domain.model.MyReservationsQuery;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationConvergence;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ResultPage;
import ar.edu.um.programacion2.turnos.reservation.domain.model.TransitionResult;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.in.ListMyReservationsUseCase;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.AppointmentGateway;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraAccessDeniedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraInvalidResponseException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraOutcomeUnknownException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraRejectedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraUnavailableException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ReservationProcessRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * CU-09: "Mis reservas". La lista sale de la tabla local, filtrada por el dueño (DPT-01, DPT-21), y la cátedra solo aporta
 * su estado para converger hacia adelante.
 * <pre>
 * [página y total del dueño, solo lectura] → ¿alguno puede cambiar? → GET /api/appointments, todas las páginas (afuera)
 *     → por cada uno que está en la cátedra: hecho de la convergencia → [releer, aplicar, guardar] → la página como quedó
 * </pre>
 * <ul>
 * <li><strong>Qué se muestra lo decide la tabla local.</strong> Una reserva de la cátedra sin proceso local se ignora:
 * {@code externalPatientId} se repite si se resetea la base, así que nunca define qué ve el usuario. Tampoco se cruza una
 * cuyo {@code externalPatientId} no es el del dueño.</li>
 * <li><strong>Una sola llamada por página</strong>, con el rango de fechas de los procesos que se cruzan, y solo si alguno
 * puede cambiar: los que tienen {@code reservationProcessId} y no están en un estado final (un final nunca cambia, así
 * que preguntar por él no aporta nada). Una página vacía (3a), sin procesos con hold (4a) o solo de historial no llama.</li>
 * <li><strong>Sin reintentos</strong>: el usuario está esperando (DPT-08). Cualquier falla de la cátedra es la marca de "no
 * actualizado" (4b, 4c), con los datos locales.</li>
 * <li><strong>Converge solo hacia adelante</strong> con {@link ReservationProcessUpdater}: relee, la máquina de estados
 * decide y, si un evento o CU-11 lo movió en el medio, gana uno y se responde el estado actual (6b).</li>
 * <li><strong>El filtro por estado se aplica antes de converger</strong>: un proceso pedido como {@code CONFIRMED} puede
 * salir {@code CANCELLED}. Se responde el estado actual, que es la verdad, y el total no se recalcula.</li>
 * </ul>
 * Es una consulta que puede escribir: lo que escribe es idempotente (aplicarlo dos veces da "sin efecto") y solo avanza.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class ListMyReservationsUseCaseImpl implements ListMyReservationsUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(ListMyReservationsUseCaseImpl.class);

    private final ReservationProcessRepository repository;
    private final ReadTransactions readTransactions;
    private final AppointmentGateway appointments;
    private final ReservationProcessUpdater updater;
    private final Clock clock;

    @Override
    public MyReservations list(MyReservationsQuery query) {
        // Paso 3: la página y el total, de la misma foto.
        ResultPage<ReservationProcess> page = readTransactions
            .template()
            .execute(status -> repository.findPageByOwner(query.userId(), query.statuses(), query.page()));

        // Paso 4 (3a y 4a si no queda ninguno).
        List<ReservationProcess> crossable = page
            .items()
            .stream()
            .filter(process -> process.getHold().isPresent() && !process.getStatus().isFinal())
            .toList();
        if (crossable.isEmpty()) {
            return new MyReservations(page, true);
        }
        String externalPatientId = crossable.getFirst().getOwner().externalPatientId();
        LocalDate from = crossable.stream().map(process -> process.getSlot().date()).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate to = crossable.stream().map(process -> process.getSlot().date()).max(Comparator.naturalOrder()).orElseThrow();
        CatedraReservationList catedra;
        try {
            catedra = appointments.listReservations(externalPatientId, from, to);
        } catch (CatedraException e) {
            logCatedraFailure(e, query.userId());
            return new MyReservations(page, false);
        }

        // Pasos 5 y 6.
        boolean upToDate = true;
        List<ReservationProcess> result = new ArrayList<>(page.items());
        for (ReservationProcess process : crossable) {
            CatedraHold hold = process.getHold().orElseThrow();
            Optional<CatedraReservation> reservation = catedra.find(hold.reservationProcessId());
            if (reservation.isEmpty()) {
                // 5a: se muestra con el estado local; si corresponde cerrarlo, lo hace CU-11.
                continue;
            }
            CatedraReservation found = reservation.orElseThrow();
            if (found.externalPatientId() != null && !found.externalPatientId().equals(externalPatientId)) {
                LOG.warn("CU-09: la reserva {} de la cátedra no es del dueño del proceso {}: no se cruza", hold.reservationProcessId(), process.getId());
                continue;
            }
            ConvergenceFact fact = ReservationConvergence.factFor(found, clock.instant());
            if (fact instanceof ConvergenceFact.Nothing) {
                // 6a: PHONE_PENDING o FAILED los cierra CU-11, con su margen.
                continue;
            }
            if (!fact.degradations().isEmpty()) {
                LOG.warn("CU-09: la cátedra informó la reserva del proceso {} con datos incompletos {}: se aplica igual", process.getId(), fact.degradations());
            }
            Optional<ReservationProcess> converged = converge(process, fact, found);
            if (converged.isEmpty()) {
                upToDate = false;
            } else {
                result.set(result.indexOf(process), converged.orElseThrow());
            }
        }
        return new MyReservations(page.withItems(result), upToDate);
    }

    /** Aplica el hecho releyendo el proceso. Vacío si no se pudo guardar (se muestra el estado leído). */
    private Optional<ReservationProcess> converge(ReservationProcess process, ConvergenceFact fact, CatedraReservation reservation) {
        Updated updated;
        try {
            updated = updater.apply(process.getId(), process.getOwner().userId(), current -> fact.applyTo(current, now()));
        } catch (RuntimeException e) {
            // 6b agotado (o la base): este proceso queda como se leyó; la marca dice que no está actualizado.
            LOG.warn("CU-09: no se pudo guardar el estado que la cátedra informa para el proceso {}: se muestra el local", process.getId(), e);
            return Optional.empty();
        }
        if (updated.result() instanceof TransitionResult.Applied applied) {
            LOG.info("CU-09: proceso {} {} -> {} (la cátedra lo informa {})", process.getId(), applied.from(), applied.to(), reservation.status());
        }
        return Optional.of(updated.process());
    }

    private Instant now() {
        return clock.instant();
    }

    /** 4b y 4c: nunca se le muestra el motivo al usuario (DPT-18); el nivel del log depende de si es nuestro. */
    private static void logCatedraFailure(CatedraException failure, long userId) {
        switch (failure) {
            case CatedraUnavailableException unavailable -> LOG.warn(
                "CU-09: la cátedra no está disponible ({}): se responden los datos locales del usuario {} sin actualizar",
                unavailable.getMessage(),
                userId
            );
            case CatedraOutcomeUnknownException unknown -> LOG.error("CU-09: resultado desconocido en una consulta, no debería pasar: {}", unknown.getMessage());
            case CatedraRejectedException rejected -> LOG.error(
                "CU-09: la cátedra rechazó el listado con {} {}: es un pedido nuestro mal armado",
                rejected.status(),
                rejected.code()
            );
            case CatedraAccessDeniedException denied -> LOG.error("CU-09: la cátedra rechazó la credencial técnica: {}", denied.getMessage());
            case CatedraInvalidResponseException invalid -> LOG.error("CU-09: la cátedra respondió fuera de su contrato: {}", invalid.getMessage());
        }
    }
}
