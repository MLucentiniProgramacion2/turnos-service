package ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catalogo;

import java.util.List;

/**
 * El JSON de la agenda vigente del catálogo, como lo fija {@code ProfessionalAgendaControllerIT} del catálogo (#35).
 * Tipos envoltorio y horas como texto: un campo ausente llega como {@code null} y el formato se valida aparte.
 */
final class CatalogoAgendaDtos {

    private CatalogoAgendaDtos() {}

    record AgendaResponse(Long catalogVersion, Professional professional, List<Schedule> weeklySchedules) {}

    record Professional(Long id, String firstName, String lastName, Boolean enabled, Category category) {}

    record Category(Long id, String name, Boolean enabled) {}

    record Schedule(Long id, String dayOfWeek, String startTime, String endTime, Integer slotDurationMinutes) {}

    record Problem(String code) {}
}
