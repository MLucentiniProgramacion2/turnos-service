package ar.edu.um.programacion2.turnos.reservation.application.service;

import ar.edu.um.programacion2.turnos.reservation.domain.model.AvailabilityResult;
import ar.edu.um.programacion2.turnos.reservation.domain.model.EventOutcome;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationEvent;
import ar.edu.um.programacion2.turnos.reservation.domain.model.StartReservationCommand;
import ar.edu.um.programacion2.turnos.reservation.domain.model.SubmitPhoneCommand;
import ar.edu.um.programacion2.turnos.reservation.domain.model.SubmitPhoneResult;
import ar.edu.um.programacion2.turnos.reservation.domain.model.StartReservationResult;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.in.GetAvailabilityUseCase;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.in.HandlePhoneRequestedUseCase;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.in.HandleProcessOutcomeUseCase;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.in.StartReservationUseCase;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.in.SubmitPhoneUseCase;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Fachada de la reserva: los adaptadores de entrada (controllers y, más adelante, el listener de Kafka) llaman acá y nunca
 * a los casos de uso ni a los adaptadores de salida.
 */
@Service
@RequiredArgsConstructor
public class ReservationService {

    private final GetAvailabilityUseCase getAvailabilityUseCase;
    private final StartReservationUseCase startReservationUseCase;
    private final HandlePhoneRequestedUseCase handlePhoneRequestedUseCase;
    private final HandleProcessOutcomeUseCase handleProcessOutcomeUseCase;
    private final SubmitPhoneUseCase submitPhoneUseCase;

    /** CU-03. */
    public AvailabilityResult getAvailability(long professionalId, LocalDate from, LocalDate to, String requestedBy) {
        return getAvailabilityUseCase.getAvailability(professionalId, from, to, requestedBy);
    }

    /** CU-04. */
    public StartReservationResult startReservation(StartReservationCommand command) {
        return startReservationUseCase.start(command);
    }

    /** CU-05. */
    public EventOutcome handlePhoneRequested(ReservationEvent.PhoneRequested event) {
        return handlePhoneRequestedUseCase.handle(event);
    }

    /** CU-07. */
    public EventOutcome handleProcessOutcome(ReservationEvent.OutcomeEvent event) {
        return handleProcessOutcomeUseCase.handle(event);
    }

    /** CU-06. */
    public SubmitPhoneResult submitPhone(SubmitPhoneCommand command) {
        return submitPhoneUseCase.submit(command);
    }
}
