package ar.edu.um.programacion2.turnos.reservation.domain.ports.in;

import ar.edu.um.programacion2.turnos.reservation.domain.model.SubmitPhoneCommand;
import ar.edu.um.programacion2.turnos.reservation.domain.model.SubmitPhoneResult;

/** CU-06: informar el teléfono de un proceso propio, publicando {@code AdditionalInformationSubmitted}. */
public interface SubmitPhoneUseCase {
    SubmitPhoneResult submit(SubmitPhoneCommand command);
}
