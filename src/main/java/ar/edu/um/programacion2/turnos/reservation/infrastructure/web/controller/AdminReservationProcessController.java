package ar.edu.um.programacion2.turnos.reservation.infrastructure.web.controller;

import ar.edu.um.programacion2.turnos.reservation.application.service.ReservationService;
import ar.edu.um.programacion2.turnos.reservation.domain.model.AdminProcessQuery;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PageQuery;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessId;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ResultPage;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.web.dto.AdminProcessParams;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.web.dto.AdminReservationProcessPageResponse;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.web.dto.AdminReservationProcessResponse;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.web.mapper.AdminReservationProcessDtoMapper;
import jakarta.validation.Valid;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Adaptador de entrada REST de CU-12: los procesos de todos los usuarios, para un administrador. <strong>Solo
 * lectura</strong> (DPT-14): no hay ningún endpoint que modifique.
 * <p>
 * Lo protege la regla de {@code /api/admin/**}: {@code ROLE_ADMIN} y no {@code ROLE_SERVICE}. No lleva además un
 * {@code @PreAuthorize}: con dos capas, ningún test detectaría que se aflojó una. Un administrador que usa los endpoints
 * comunes ({@code /api/reservation-processes}) sigue viendo solo lo suyo.
 */
@RestController
@RequestMapping("/api/admin/reservation-processes")
@RequiredArgsConstructor
public class AdminReservationProcessController {

    private final ReservationService reservationService;
    private final AdminReservationProcessDtoMapper mapper;

    @GetMapping
    public AdminReservationProcessPageResponse list(@Valid AdminProcessParams params) {
        ResultPage<ReservationProcess> page = reservationService.listAllReservationProcesses(
            new AdminProcessQuery(params.userId(), Set.copyOf(params.statusOrEmpty()), new PageQuery(params.pageOrDefault(), params.sizeOrDefault()))
        );
        return new AdminReservationProcessPageResponse(
            page.number(),
            page.size(),
            page.totalElements(),
            page.totalPages(),
            page.items().stream().map(mapper::toResponse).toList()
        );
    }

    /** 5a: un proceso que no existe es {@code 404}. */
    @GetMapping("/{id}")
    public AdminReservationProcessResponse find(@PathVariable("id") UUID id) {
        return reservationService.findAnyReservationProcess(new ReservationProcessId(id)).map(mapper::toResponse).orElseThrow(ReservationErrorResponses::processNotFound);
    }
}
