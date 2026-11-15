package ar.edu.um.programacion2.turnos.reservation.application.usecases;

import ar.edu.um.programacion2.turnos.reservation.domain.model.Cancellation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.Closing;
import ar.edu.um.programacion2.turnos.reservation.domain.model.Confirmation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.EventOutcome;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PhoneRejection;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationEvent;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationEvent.AppointmentCancelled;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationEvent.AppointmentConfirmed;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationEvent.PhoneRejected;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationEvent.ProcessExpired;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationEvent.ProcessInvalid;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.in.HandleProcessOutcomeUseCase;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * CU-07: <strong>un evento, una transición del modelo.</strong> El caso de uso no pregunta en qué estado está el proceso:
 * llama al hecho que corresponde y el modelo decide si aplica o no tiene efecto (finales, {@code CONFIRMED} que solo sale a
 * {@code CANCELLED}, un rechazo fuera de {@code PHONE_SUBMITTED}...). La barrera, la búsqueda y el guardado son los del
 * {@link ReservationEventProcessor}, los mismos que CU-05.
 * <p>
 * {@code AppointmentCancelled} va a {@code cancelled}, que solo vale desde {@code CONFIRMED}; no a
 * {@code cancelledByReconciliation}, que es de CU-09 y CU-11.
 * <p>
 * Los instantes de confirmación y cancelación son el {@code occurredAt} del evento, que es cuándo pasó en la cátedra; si no
 * vino, el del reloj.
 */
@Component
@RequiredArgsConstructor
public class HandleProcessOutcomeUseCaseImpl implements HandleProcessOutcomeUseCase {

    private final ReservationEventProcessor processor;

    @Override
    public EventOutcome handle(ReservationEvent.OutcomeEvent event) {
        return switch (event) {
            case AppointmentConfirmed confirmed -> processor.process(confirmed, (process, now) ->
                process.confirmed(new Confirmation(confirmed.reservationId(), orNow(confirmed.occurredAt(), now)), now)
            );
            case PhoneRejected rejected -> processor.process(rejected, (process, now) ->
                process.phoneRejected(new PhoneRejection(rejected.reason(), rejected.message()), rejected.expiresAt(), now)
            );
            case ProcessExpired expired -> processor.process(expired, (process, now) -> process.expired(new Closing(expired.reason(), null), now));
            case ProcessInvalid invalid -> processor.process(invalid, (process, now) -> process.invalid(new Closing(invalid.reason(), null), now));
            case AppointmentCancelled cancelled -> processor.process(cancelled, (process, now) ->
                process.cancelled(new Cancellation(cancelled.reason(), orNow(cancelled.occurredAt(), now)), now)
            );
        };
    }

    private static Instant orNow(Instant occurredAt, Instant now) {
        return occurredAt != null ? occurredAt : now;
    }
}
