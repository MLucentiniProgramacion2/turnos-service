package ar.edu.um.programacion2.turnos.reservation.infrastructure.web.dto;

import java.util.List;

/** Respuesta de {@code GET /api/admin/reservation-processes} (CU-12), con el formato de página del catálogo. */
public record AdminReservationProcessPageResponse(int page, int size, long totalElements, int totalPages, List<AdminReservationProcessResponse> processes) {}
