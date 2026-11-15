package ar.edu.um.programacion2.turnos.reservation.infrastructure.web.controller;

import ar.edu.um.programacion2.turnos.reservation.application.service.ReservationService;
import ar.edu.um.programacion2.turnos.reservation.domain.model.StartReservationResult;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.web.dto.ReservationProcessResponse;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.web.dto.StartReservationRequest;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.web.mapper.ReservationProcessDtoMapper;
import ar.edu.um.programacion2.turnos.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Adaptador de entrada REST de los procesos de reserva: {@code POST /api/reservation-processes} (CU-04).
 * <p>
 * Lo protege la regla de {@code /api/**}: {@code 401} sin token o con uno inválido, {@code 403} con el token técnico de
 * servicio. <strong>La identidad sale del token</strong> ({@code userId} y login, DPT-21): el cuerpo no trae ningún dato
 * del usuario, así nadie puede reservar a nombre de otro.
 * <p>
 * El camino normal responde {@code 202} y un pedido repetido {@code 200} (3a): los distingue el tipo del resultado, que
 * es {@code sealed}. El {@code switch} no tiene {@code default}.
 */
@RestController
@RequestMapping("/api/reservation-processes")
@RequiredArgsConstructor
public class ReservationProcessController {

    private final ReservationService reservationService;
    private final ReservationProcessDtoMapper mapper;

    @PostMapping
    public ResponseEntity<ReservationProcessResponse> startReservation(@Valid @RequestBody StartReservationRequest request) {
        // Un token de usuario emitido por turnos siempre trae el userId (ADR 0003); sin él no hay a nombre de quién reservar.
        long userId = SecurityUtils.getCurrentUserId().orElseThrow(ReservationErrorResponses::userNotFound);
        String login = SecurityUtils.getCurrentUserLogin().orElse(null);
        StartReservationResult result = reservationService.startReservation(mapper.toCommand(request, userId, login));
        return switch (result) {
            case StartReservationResult.Started started -> ResponseEntity.accepted().body(mapper.toResponse(started.process()));
            case StartReservationResult.AlreadyExists existing -> ResponseEntity.ok(mapper.toResponse(existing.process()));
            case StartReservationResult.AnotherInProgress another -> throw ReservationErrorResponses.reservationInProgress(another.existing());
            case StartReservationResult.InvalidDate invalid -> throw ReservationErrorResponses.invalidDateRange(
                "La fecha del turno ya pasó o está más allá de los días que se ofrecen"
            );
            case StartReservationResult.InvalidSlot invalid -> throw ReservationErrorResponses.invalidSlot();
            case StartReservationResult.ProfessionalNotFound notFound -> throw ReservationErrorResponses.professionalNotFound();
            case StartReservationResult.ProfessionalDisabled disabled -> throw ReservationErrorResponses.professionalDisabled();
            case StartReservationResult.UserNotFound notFound -> throw ReservationErrorResponses.userNotFound();
            case StartReservationResult.ProfileIncomplete incomplete -> throw ReservationErrorResponses.profileIncomplete();
            case StartReservationResult.SlotNotAvailable taken -> throw ReservationErrorResponses.slotNotAvailable();
            case StartReservationResult.Expired expired -> throw ReservationErrorResponses.reservationExpired();
            case StartReservationResult.HoldOutcomeUnknown unknown -> throw ReservationErrorResponses.holdOutcomeUnknown();
            case StartReservationResult.HoldNotRecorded notRecorded -> throw ReservationErrorResponses.holdNotRecorded();
            case StartReservationResult.CatalogUnavailable unavailable -> throw ReservationErrorResponses.catalogUnavailable(unavailable.retryAfter());
            case StartReservationResult.CatedraUnavailable unavailable -> throw ReservationErrorResponses.catedraUnavailable();
            case StartReservationResult.UpstreamInvalidResponse invalid -> throw ReservationErrorResponses.upstreamInvalidResponse();
            case StartReservationResult.InternalError error -> throw ReservationErrorResponses.internalError();
        };
    }
}
