package ar.edu.um.programacion2.turnos.reservation.infrastructure.web.dto;

import java.util.List;

/**
 * Respuesta de {@code GET /api/reservation-processes} (CU-09): una página de procesos propios con el formato de página del
 * catálogo, y la marca de si el estado de la cátedra está al día.
 *
 * @param page número de página pedido, desde 0
 * @param size tamaño de página pedido
 * @param totalElements procesos que cumplen el filtro, en todas las páginas
 * @param totalPages cantidad de páginas; una página fuera de rango viene vacía con estos totales
 * @param catedraUpToDate {@code false} si hacía falta consultar a la cátedra y no se pudo: los procesos se muestran con el
 *     estado local
 * @param processes cada proceso con el mismo DTO que CU-04, CU-06 y CU-08, ya convergido con la cátedra
 */
public record ReservationProcessPageResponse(
    int page,
    int size,
    long totalElements,
    int totalPages,
    boolean catedraUpToDate,
    List<ReservationProcessResponse> processes
) {}
