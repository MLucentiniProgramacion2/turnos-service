package ar.edu.um.programacion2.turnos.reservation.infrastructure.web.controller;

import ar.edu.um.programacion2.turnos.reservation.application.service.ReservationService;
import ar.edu.um.programacion2.turnos.reservation.domain.model.AvailabilityPolicy;
import ar.edu.um.programacion2.turnos.reservation.domain.model.AvailabilityResult;
import ar.edu.um.programacion2.turnos.reservation.domain.model.RangeViolation;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.web.dto.AvailabilityParams;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.web.dto.AvailabilityResponse;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.web.mapper.AvailabilityDtoMapper;
import ar.edu.um.programacion2.turnos.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Adaptador de entrada REST de CU-03: {@code GET /api/professionals/{professionalId}/availability?from=&to=}.
 * <p>
 * Lo protege la regla de {@code /api/**} de {@code SecurityConfiguration}: sin token o con uno inválido, {@code 401}; con
 * el token técnico de servicio o sin {@code ROLE_USER}/{@code ROLE_ADMIN}, {@code 403}.
 * <p>
 * El login sale del token y viaja solo para el log del catálogo ({@code X-User-Login}). El resultado del caso de uso es
 * {@code sealed}: el {@code switch} no tiene {@code default}, así que un resultado nuevo no compila hasta decidir qué se
 * responde.
 */
@RestController
@RequestMapping("/api/professionals")
@RequiredArgsConstructor
public class AvailabilityController {

    private final ReservationService reservationService;
    private final AvailabilityDtoMapper availabilityDtoMapper;

    @GetMapping("/{professionalId}/availability")
    public AvailabilityResponse getAvailability(@PathVariable("professionalId") Long professionalId, @Valid AvailabilityParams params) {
        String login = SecurityUtils.getCurrentUserLogin().orElse(null);
        AvailabilityResult result = reservationService.getAvailability(professionalId, params.from(), params.to(), login);
        return switch (result) {
            case AvailabilityResult.Available available -> availabilityDtoMapper.toResponse(available.availability());
            case AvailabilityResult.InvalidRange invalid -> throw ReservationErrorResponses.invalidDateRange(describe(invalid.violation()));
            case AvailabilityResult.ProfessionalNotFound notFound -> throw ReservationErrorResponses.professionalNotFound();
            case AvailabilityResult.ProfessionalDisabled disabled -> throw ReservationErrorResponses.professionalDisabled();
            case AvailabilityResult.CatalogUnavailable unavailable -> throw ReservationErrorResponses.catalogUnavailable(unavailable.retryAfter());
            case AvailabilityResult.CatedraUnavailable unavailable -> throw ReservationErrorResponses.catedraUnavailable();
            case AvailabilityResult.UpstreamInvalidResponse invalid -> throw ReservationErrorResponses.upstreamInvalidResponse();
            case AvailabilityResult.InternalError error -> throw ReservationErrorResponses.internalError();
        };
    }

    private static String describe(RangeViolation violation) {
        return switch (violation) {
            case FROM_AFTER_TO -> "La fecha desde no puede ser posterior a la fecha hasta";
            case TOO_LONG -> "El rango no puede tener más de " + AvailabilityPolicy.MAX_RANGE_DAYS + " días";
            case IN_THE_PAST -> "No se pueden consultar fechas pasadas";
            case BEYOND_HORIZON -> "La fecha hasta está más allá de los días que se ofrecen";
        };
    }
}
