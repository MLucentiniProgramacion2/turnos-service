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

    /** De un {@code problem+json} solo interesa el {@code code}: nunca se decide por {@code detail} (contrato §13). */
    record Problem(String code) {}
}
