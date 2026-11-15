package ar.edu.um.programacion2.turnos.reservation.infrastructure.web.mapper;

import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraHold;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.StartReservationCommand;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.web.dto.ReservationProcessResponse;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.web.dto.StartReservationRequest;
import org.springframework.stereotype.Component;

/** Traduce entre los DTO de la reserva y el dominio. Es el único lugar que decide qué datos del proceso ve la app. */
@Component
public class ReservationProcessDtoMapper {

    /** El pedido con la identidad que el controller sacó del token. */
    public StartReservationCommand toCommand(StartReservationRequest request, long userId, String login) {
        return new StartReservationCommand(userId, login, request.professionalId(), request.date(), request.startTime());
    }

    public ReservationProcessResponse toResponse(ReservationProcess process) {
        return new ReservationProcessResponse(
            process.getId().value(),
            process.getStatus().name(),
            process.getHold().map(CatedraHold::expiresAt).orElse(null),
            new ReservationProcessResponse.Appointment(
                process.getSlot().professionalId(),
                process.getSlot().date(),
                process.getSlot().startTime(),
                process.getSlot().endTime()
            ),
            new ReservationProcessResponse.Professional(
                process.getProfessional().firstName(),
                process.getProfessional().lastName(),
                process.getProfessional().categoryName(),
                process.getProfessional().slotDurationMinutes()
            ),
            process.getPhoneRequest().map(request -> new ReservationProcessResponse.PhoneRequest(request.message())).orElse(null),
            process.getRejection().map(rejection -> new ReservationProcessResponse.Rejection(rejection.reason(), rejection.message())).orElse(null),
            process.getConfirmation().map(confirmation -> new ReservationProcessResponse.Confirmation(confirmation.confirmedAt())).orElse(null),
            process
                .getCancellation()
                .map(cancellation -> new ReservationProcessResponse.Cancellation(cancellation.reason(), cancellation.cancelledAt()))
                .orElse(null),
            process.getClosing().map(closing -> new ReservationProcessResponse.Closing(closing.reason(), closing.message())).orElse(null)
        );
    }
}
