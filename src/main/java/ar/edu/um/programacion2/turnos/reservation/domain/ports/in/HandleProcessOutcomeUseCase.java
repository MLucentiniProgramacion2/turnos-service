package ar.edu.um.programacion2.turnos.reservation.domain.ports.in;

import ar.edu.um.programacion2.turnos.reservation.domain.model.EventOutcome;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationEvent;

/** CU-07: un resultado del proceso (confirmación, rechazo, vencimiento, invalidez o cancelación). */
public interface HandleProcessOutcomeUseCase {
    EventOutcome handle(ReservationEvent.OutcomeEvent event);
}
