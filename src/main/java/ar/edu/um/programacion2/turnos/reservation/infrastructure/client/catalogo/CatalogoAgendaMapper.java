package ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catalogo;

import ar.edu.um.programacion2.turnos.reservation.domain.model.ProfessionalAgenda;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ProfessionalAgenda.AgendaCategory;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ProfessionalAgenda.AgendaProfessional;
import ar.edu.um.programacion2.turnos.reservation.domain.model.WeeklySchedule;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/**
 * Traduce la agenda del catálogo al dominio. Lanza {@link IllegalArgumentException} con el campo que falla y sin su valor.
 * <p>
 * El catálogo manda las horas siempre como {@code HH:mm:ss}, pero también se acepta {@code HH:mm}, como pide el contrato
 * de la cátedra para sus horas (§4): son las mismas horas, publicadas por la cátedra. Lo que no es ninguno de los dos
 * (fracciones de segundo, {@code 9:00}, {@code 24:00}) es inválido.
 */
final class CatalogoAgendaMapper {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm[:ss]").withResolverStyle(ResolverStyle.STRICT);

    private CatalogoAgendaMapper() {}

    static ProfessionalAgenda toDomain(CatalogoAgendaDtos.AgendaResponse response, long requestedId) {
        required(response, "el cuerpo");
        CatalogoAgendaDtos.Professional professional = required(response.professional(), "professional");
        if (required(professional.id(), "professional.id") != requestedId) {
            throw new IllegalArgumentException("professional.id no es el pedido");
        }
        CatalogoAgendaDtos.Category category = required(professional.category(), "professional.category");
        return new ProfessionalAgenda(
            required(response.catalogVersion(), "catalogVersion"),
            new AgendaProfessional(
                professional.id(),
                required(professional.firstName(), "professional.firstName"),
                required(professional.lastName(), "professional.lastName"),
                required(professional.enabled(), "professional.enabled"),
                new AgendaCategory(
                    required(category.id(), "professional.category.id"),
                    required(category.name(), "professional.category.name"),
                    required(category.enabled(), "professional.category.enabled")
                )
            ),
            required(response.weeklySchedules(), "weeklySchedules").stream().map(CatalogoAgendaMapper::toSchedule).toList()
        );
    }

    private static WeeklySchedule toSchedule(CatalogoAgendaDtos.Schedule schedule) {
        required(schedule, "un horario");
        return new WeeklySchedule(
            required(schedule.id(), "weeklySchedules.id"),
            dayOfWeek(schedule.dayOfWeek()),
            time(schedule.startTime(), "weeklySchedules.startTime"),
            time(schedule.endTime(), "weeklySchedules.endTime"),
            required(schedule.slotDurationMinutes(), "weeklySchedules.slotDurationMinutes")
        );
    }

    private static DayOfWeek dayOfWeek(String value) {
        try {
            return DayOfWeek.valueOf(required(value, "weeklySchedules.dayOfWeek"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("weeklySchedules.dayOfWeek no es MONDAY a SUNDAY");
        }
    }

    private static LocalTime time(String value, String field) {
        try {
            return LocalTime.parse(required(value, field), TIME);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " no es una hora HH:mm o HH:mm:ss");
        }
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("Falta " + field);
        }
        return value;
    }
}
