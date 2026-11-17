package ar.edu.um.programacion2.turnos.reservation.application.usecases;

import ar.edu.um.programacion2.turnos.reservation.application.usecases.ReservationProcessUpdater.Updated;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CancelReservationCommand;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CancelReservationResult;
import ar.edu.um.programacion2.turnos.reservation.domain.model.Cancellation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraCancellation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraHold;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.TransitionResult;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.in.CancelReservationUseCase;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.AppointmentGateway;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraAccessDeniedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraInvalidResponseException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraOutcomeUnknownException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraRejectedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraUnavailableException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ReservationProcessRepository;
import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * CU-10: cancela una reserva propia confirmada.
 * <pre>
 * [buscar por id y dueño] → canCancel → POST /api/appointments/{id}/cancel (afuera) → [releer, cancelled, guardar]
 * </pre>
 * <ul>
 * <li><strong>El dueño:</strong> el proceso se busca por su id y el {@code userId} del token; uno ajeno es lo mismo que uno
 * que no existe (DPT-19). Nunca se cancela una reserva ajena.</li>
 * <li><strong>El estado lo decide el modelo</strong> ({@code canCancel}): ya cancelado responde {@code 200} sin llamar a la
 * cátedra (4a); otro estado, {@code 409} (4b).</li>
 * <li><strong>El {@code POST} va afuera de toda transacción y sin reintentos</strong> (DPT-08): el usuario está esperando.
 * Es una operación con efectos: sin conectar, seguro que no se aplicó; después de conectar, resultado desconocido (5a).
 * En los dos el proceso queda {@code CONFIRMED}. Que el usuario reintente es seguro: la cátedra devuelve el estado de una
 * reserva ya cancelada (§12). Si se aplicó, converge por {@code AppointmentCancelled} (CU-07) o por CU-09.</li>
 * <li><strong>El guardado relee</strong> ({@link ReservationProcessUpdater}): si {@code AppointmentCancelled} llegó antes
 * (7a), el modelo da {@code FINAL_STATE} y se responde el estado actual.</li>
 * <li><strong>El motivo es del usuario:</strong> se guarda el que mandó (la respuesta de la cátedra no lo trae) y nunca va
 * al log.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NEVER)
public class CancelReservationUseCaseImpl implements CancelReservationUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(CancelReservationUseCaseImpl.class);

    private final ReservationProcessRepository repository;
    private final ReadTransactions readTransactions;
    private final AppointmentGateway appointments;
    private final ReservationProcessUpdater updater;
    private final Clock clock;

    @Override
    public CancelReservationResult cancel(CancelReservationCommand command) {
        // Paso 3.
        Optional<ReservationProcess> found = readTransactions.template().execute(status -> repository.findByIdAndOwner(command.processId(), command.userId()));
        if (found.isEmpty()) {
            return new CancelReservationResult.NotFound();
        }
        ReservationProcess process = found.orElseThrow();

        // Paso 4.
        switch (process.canCancel()) {
            case ALREADY_CANCELLED -> {
                LOG.info("CU-10: el proceso {} ya estaba cancelado: no se llama a la cátedra", process.getId());
                return new CancelReservationResult.Cancelled(process);
            }
            case NOT_CANCELLABLE -> {
                return new CancelReservationResult.NotCancellable(process.getStatus());
            }
            case ALLOWED -> {
                // Sigue.
            }
        }

        // Pasos 5 y 6. Un CONFIRMED siempre tiene hold.
        CatedraHold hold = process.getHold().orElseThrow();
        CatedraCancellation cancellation;
        try {
            cancellation = appointments.cancel(hold.reservationProcessId(), command.reason());
        } catch (CatedraException e) {
            return failure(process, e);
        }

        // Pasos 7 y 8, y 7a.
        try {
            Updated updated = updater.apply(process.getId(), command.userId(), current ->
                current.cancelled(new Cancellation(command.reason(), cancellation.cancelledAt()), clock.instant())
            );
            if (updated.result() instanceof TransitionResult.Applied) {
                LOG.info("CU-10: proceso {} CONFIRMED -> CANCELLED (reservationProcessId={})", process.getId(), hold.reservationProcessId());
            } else {
                LOG.info("CU-10: el proceso {} ya estaba {} al guardar la cancelación (llegó el evento antes)", process.getId(), updated.process().getStatus());
            }
            return new CancelReservationResult.Cancelled(updated.process());
        } catch (RuntimeException e) {
            // La cancelación ya está en la cátedra: el proceso converge por AppointmentCancelled (CU-07) o por CU-09.
            LOG.error(
                "CU-10: la reserva se canceló en la cátedra pero no se pudo guardar; el proceso {} queda CONFIRMED hasta que converja (reservationProcessId={})",
                process.getId(),
                hold.reservationProcessId(),
                e
            );
            return new CancelReservationResult.InternalError();
        }
    }

    /** 5a, 6a y 6b. El proceso no cambia en ningún caso: si la cancelación se aplicó, converge por CU-07 o CU-09. */
    private static CancelReservationResult failure(ReservationProcess process, CatedraException failure) {
        return switch (failure) {
            case CatedraUnavailableException unavailable -> {
                LOG.warn("CU-10: no se pudo conectar con la cátedra para cancelar el proceso {}: no se canceló", process.getId());
                yield new CancelReservationResult.CatedraUnavailable();
            }
            case CatedraOutcomeUnknownException unknown -> {
                LOG.warn("CU-10: resultado desconocido al cancelar el proceso {}: queda CONFIRMED y converge si se aplicó", process.getId());
                yield new CancelReservationResult.OutcomeUnknown();
            }
            case CatedraRejectedException rejected -> switch (rejected.code()) {
                case RESERVATION_NOT_CANCELLABLE -> {
                    // 6a: la cátedra no la tiene CONFIRMED ni CANCELLED. Discrepancia: la corrige CU-09 o CU-11.
                    LOG.warn("CU-10: la cátedra dice que el proceso {} no se puede cancelar y localmente está {}", process.getId(), process.getStatus());
                    yield new CancelReservationResult.NotCancellable(process.getStatus());
                }
                default -> {
                    // 6b: RESERVATION_NOT_FOUND, RESERVATION_OWNER_MISMATCH, VALIDATION_ERROR... solo se explican por un error
                    // propio o de la cuenta técnica (DPT-18).
                    LOG.error("CU-10: la cátedra rechazó la cancelación del proceso {} con {} {}", process.getId(), rejected.status(), rejected.code());
                    yield new CancelReservationResult.InternalError();
                }
            };
            case CatedraAccessDeniedException denied -> {
                LOG.error("CU-10: la cátedra rechazó la credencial técnica al cancelar: {}", denied.getMessage());
                yield new CancelReservationResult.CatedraUnavailable();
            }
            case CatedraInvalidResponseException invalid -> {
                LOG.error("CU-10: la cátedra respondió fuera de su contrato al cancelar: {}", invalid.getMessage());
                yield new CancelReservationResult.UpstreamInvalidResponse();
            }
        };
    }
}
