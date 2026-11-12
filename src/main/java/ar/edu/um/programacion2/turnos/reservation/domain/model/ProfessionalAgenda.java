package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * La agenda vigente de un profesional según el catálogo (CU-07 del catálogo, contrato del #35): el profesional con su
 * categoría y sus horarios semanales habilitados, todo de la misma versión del catálogo. Turnos no la guarda: la pide en
 * cada operación (DPT-11). Un profesional deshabilitado viene sin horarios.
 */
public record ProfessionalAgenda(long catalogVersion, AgendaProfessional professional, List<WeeklySchedule> weeklySchedules) {
    public ProfessionalAgenda {
        Objects.requireNonNull(professional, "Falta el profesional");
        weeklySchedules = List.copyOf(Objects.requireNonNull(weeklySchedules, "Faltan los horarios"));
        if (!professional.enabled() && !weeklySchedules.isEmpty()) {
            throw new IllegalArgumentException("Un profesional deshabilitado no tiene horarios en la agenda");
        }
    }

    /** El profesional, su categoría y su estado, tal como los informó el catálogo. */
    public record AgendaProfessional(long id, String firstName, String lastName, boolean enabled, AgendaCategory category) {
        public AgendaProfessional {
            Texts.requireText(firstName, "el nombre del profesional");
            Texts.requireText(lastName, "el apellido del profesional");
            Objects.requireNonNull(category, "Falta la categoría");
        }
    }

    /** La categoría se informa, pero estar deshabilitada no cambia la agenda (CU-07 4e del catálogo). */
    public record AgendaCategory(long id, String name, boolean enabled) {
        public AgendaCategory {
            Texts.requireText(name, "el nombre de la categoría");
        }
    }
}
