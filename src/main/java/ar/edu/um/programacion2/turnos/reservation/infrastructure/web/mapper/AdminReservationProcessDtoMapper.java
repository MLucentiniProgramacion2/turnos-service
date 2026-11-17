package ar.edu.um.programacion2.turnos.reservation.infrastructure.web.mapper;

import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraHold;
import ar.edu.um.programacion2.turnos.reservation.domain.model.Confirmation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PhoneRequest;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.web.dto.AdminReservationProcessResponse;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.web.dto.ReservationProcessResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Traduce un proceso al DTO de administrador (CU-12). Es el único lugar que decide qué ve un administrador: los
 * identificadores de diagnóstico sí; el teléfono, los nombres del paciente y el texto del motivo de cancelación, no.
 */
@Component
@RequiredArgsConstructor
public class AdminReservationProcessDtoMapper {

    private final ReservationProcessDtoMapper userMapper;

    public AdminReservationProcessResponse toResponse(ReservationProcess process) {
        // Lo que comparte con la vista del usuario sale del mismo mapper, así los dos formatos no se separan.
        ReservationProcessResponse common = userMapper.toResponse(process);
        CatedraHold hold = process.getHold().orElse(null);
        return new AdminReservationProcessResponse(
            process.getId().value(),
            new AdminReservationProcessResponse.Owner(process.getOwner().userId(), process.getOwner().login()),
            process.getStatus().name(),
            process.getCreatedAt(),
            process.getStatusChangedAt(),
            common.expiresAt(),
            common.appointment(),
            common.professional(),
            new AdminReservationProcessResponse.Integration(
                hold == null ? null : hold.holdId(),
                hold == null ? null : hold.reservationProcessId(),
                process.getConfirmation().map(Confirmation::reservationId).orElse(null),
                process.getPhoneRequest().map(PhoneRequest::requestEventId).orElse(null),
                process.getLastSubmittedEventId().orElse(null)
            ),
            common.rejection(),
            common.confirmation(),
            process
                .getCancellation()
                .map(cancellation -> new AdminReservationProcessResponse.Cancellation(cancellation.cancelledAt(), cancellation.reason() != null))
                .orElse(null),
            common.closing()
        );
    }
}
