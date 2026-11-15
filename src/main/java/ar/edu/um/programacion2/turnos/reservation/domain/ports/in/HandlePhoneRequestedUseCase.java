package ar.edu.um.programacion2.turnos.reservation.domain.ports.in;

import ar.edu.um.programacion2.turnos.reservation.domain.model.EventOutcome;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationEvent;

/** CU-05: la cátedra pidió el teléfono ({@code AdditionalInformationRequested}). */
public interface HandlePhoneRequestedUseCase {
    EventOutcome handle(ReservationEvent.PhoneRequested event);
}
