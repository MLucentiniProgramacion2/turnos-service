package ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catedra;

import java.util.List;

/**
 * Los JSON de la API de la cátedra que usa turnos (contrato §8 a §10 y §13), tal como viajan. Tipos envoltorio y fechas
 * como texto: así un campo ausente llega como {@code null} y el formato lo valida {@link CatedraMapper}, en vez de que
 * Jackson ponga un 0 o falle con un mensaje que nombra tipos de Java (ADR 0004).
 */
final class CatedraDtos {

    private CatedraDtos() {}

    record OccupanciesResponse(Long professionalId, String from, String to, List<OccupancyItem> occupancies) {}

    record OccupancyItem(String date, String startTime, String status, String expiresAt) {}

    record HoldRequest(Long professionalId, String date, String startTime, String externalPatientId) {}

    record HoldResponse(String holdId, String reservationProcessId, String status, String expiresAt) {}

    record ConfirmRequest(String externalPatientId, String patientFirstName, String patientLastName) {}

    record ConfirmResponse(String reservationProcessId, String status, String expiresAt) {}

    /**
     * Un ítem de {@code GET /api/appointments} (contrato §11). <strong>No declara {@code patientPhone},
     * {@code patientFirstName} ni {@code patientLastName}</strong>: el {@code JsonMapper} ignora lo que no está declarado,
     * así esos datos personales no llegan a ningún objeto de turnos. {@code reservationId} es un número: un texto o un
     * decimal hacen ilegible la página entera.
     */
    record AppointmentItem(
        Long reservationId,
        String reservationProcessId,
        String externalPatientId,
        String appointmentDate,
        String startTime,
        String status,
        String confirmedAt,
        String cancelledAt,
        String cancellationReason
    ) {}

    /** Cuerpo de la cancelación (contrato §12): opcional; si no hay motivo, no se manda cuerpo. */
    record CancelRequest(String reason) {}

    /** {@code 200} de la cancelación (contrato §12). */
    record CancelResponse(Long reservationId, String reservationProcessId, String status, String cancelledAt) {}

    /** De un {@code problem+json} solo interesa el {@code code}: nunca se decide por {@code detail} (contrato §13). */
    record Problem(String code) {}
}
