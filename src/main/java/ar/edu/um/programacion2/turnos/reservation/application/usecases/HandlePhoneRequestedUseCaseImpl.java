package ar.edu.um.programacion2.turnos.reservation.application.usecases;

import ar.edu.um.programacion2.turnos.reservation.domain.model.EventOutcome;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PhoneRequest;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationEvent;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.in.HandlePhoneRequestedUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * CU-05: guarda el {@code eventId} del pedido (se manda después como {@code requestEventId}), el vencimiento y el mensaje,
 * y deja el proceso en {@code PHONE_REQUESTED}. Qué estados lo aceptan lo decide el modelo ({@code phoneRequested}):
 * {@code HOLD_CREATED} también, porque el evento puede llegar antes que el {@code 202} del confirm (3.3a).
 */
@Component
@RequiredArgsConstructor
public class HandlePhoneRequestedUseCaseImpl implements HandlePhoneRequestedUseCase {

    private final ReservationEventProcessor processor;

    @Override
    public EventOutcome handle(ReservationEvent.PhoneRequested event) {
        PhoneRequest request = new PhoneRequest(event.eventId(), event.message());
        return processor.process(event, (process, now) -> process.phoneRequested(request, event.expiresAt(), now));
    }
}
