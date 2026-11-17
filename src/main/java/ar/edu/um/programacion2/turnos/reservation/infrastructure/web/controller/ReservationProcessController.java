package ar.edu.um.programacion2.turnos.reservation.infrastructure.web.controller;

import ar.edu.um.programacion2.turnos.reservation.application.service.ReservationService;
import ar.edu.um.programacion2.turnos.reservation.domain.model.MyReservations;
import ar.edu.um.programacion2.turnos.reservation.domain.model.MyReservationsQuery;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PageQuery;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessId;
import ar.edu.um.programacion2.turnos.reservation.domain.model.StartReservationResult;
import ar.edu.um.programacion2.turnos.reservation.domain.model.SubmitPhoneCommand;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ResultPage;
import ar.edu.um.programacion2.turnos.reservation.domain.model.SubmitPhoneResult;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.web.dto.MyReservationsParams;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.web.dto.ReservationProcessPageResponse;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.web.dto.ReservationProcessResponse;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.web.dto.StartReservationRequest;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.web.dto.SubmitPhoneRequest;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.web.mapper.ReservationProcessDtoMapper;
import ar.edu.um.programacion2.turnos.security.SecurityUtils;
import jakarta.validation.Valid;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Adaptador de entrada REST de los procesos de reserva: {@code POST /api/reservation-processes} (CU-04),
 * {@code POST /api/reservation-processes/{id}/phone} (CU-06), {@code GET /api/reservation-processes/{id}} (CU-08) y
 * {@code GET /api/reservation-processes} (CU-09).
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

    /**
     * CU-06. El proceso se busca con el {@code userId} del token: uno ajeno responde {@code 404}, igual que uno que no
     * existe. Un {@code id} que no es un UUID da {@code 400} sin repetir el valor ({@code ExceptionTranslator}).
     */
    @PostMapping("/{id}/phone")
    public ResponseEntity<ReservationProcessResponse> submitPhone(@PathVariable("id") UUID id, @Valid @RequestBody SubmitPhoneRequest request) {
        long userId = SecurityUtils.getCurrentUserId().orElseThrow(ReservationErrorResponses::userNotFound);
        SubmitPhoneResult result = reservationService.submitPhone(new SubmitPhoneCommand(userId, new ReservationProcessId(id), request.phoneNumber()));
        return switch (result) {
            case SubmitPhoneResult.Submitted submitted -> ResponseEntity.accepted().body(mapper.toResponse(submitted.process()));
            case SubmitPhoneResult.NotFound notFound -> throw ReservationErrorResponses.processNotFound();
            case SubmitPhoneResult.NotRequestedYet notRequested -> throw ReservationErrorResponses.phoneNotRequested();
            case SubmitPhoneResult.AlreadySubmitted alreadySubmitted -> throw ReservationErrorResponses.phoneAlreadySubmitted();
            case SubmitPhoneResult.Closed closed -> throw ReservationErrorResponses.processClosed(closed.status());
            case SubmitPhoneResult.DeadlinePassed deadlinePassed -> throw ReservationErrorResponses.reservationExpired();
            case SubmitPhoneResult.InvalidPhone invalidPhone -> throw ReservationErrorResponses.invalidPhone();
            case SubmitPhoneResult.Unconfirmed unconfirmed -> throw ReservationErrorResponses.phoneSubmissionUnconfirmed();
            case SubmitPhoneResult.SaveFailed saveFailed -> throw ReservationErrorResponses.internalError();
        };
    }

    /**
     * CU-08. La app lo consulta cada 2 s (DPT-04). El proceso se busca con el {@code userId} del token: uno ajeno responde
     * {@code 404}, igual que uno que no existe, también para un administrador (DPT-14: el acceso ampliado es solo de
     * {@code /api/admin/...}). Un {@code id} que no es un UUID da {@code 400} sin repetir el valor.
     */
    @GetMapping("/{id}")
    public ReservationProcessResponse getReservationProcess(@PathVariable("id") UUID id) {
        long userId = SecurityUtils.getCurrentUserId().orElseThrow(ReservationErrorResponses::userNotFound);
        return reservationService
            .getReservationProcess(userId, new ReservationProcessId(id))
            .map(mapper::toResponse)
            .orElseThrow(ReservationErrorResponses::processNotFound);
    }

    /**
     * CU-09: los procesos del usuario del token (un administrador también ve solo los suyos, DPT-14), ya convergidos con la
     * cátedra. Si la cátedra no respondió, {@code 200} igual, con {@code catedraUpToDate: false} (4b, 4c).
     */
    @GetMapping
    public ReservationProcessPageResponse listMyReservations(@Valid MyReservationsParams params) {
        long userId = SecurityUtils.getCurrentUserId().orElseThrow(ReservationErrorResponses::userNotFound);
        MyReservations result = reservationService.listMyReservations(
            new MyReservationsQuery(userId, Set.copyOf(params.statusOrEmpty()), new PageQuery(params.pageOrDefault(), params.sizeOrDefault()))
        );
        ResultPage<ReservationProcess> page = result.page();
        return new ReservationProcessPageResponse(
            page.number(),
            page.size(),
            page.totalElements(),
            page.totalPages(),
            result.catedraUpToDate(),
            page.items().stream().map(mapper::toResponse).toList()
        );
    }
}
