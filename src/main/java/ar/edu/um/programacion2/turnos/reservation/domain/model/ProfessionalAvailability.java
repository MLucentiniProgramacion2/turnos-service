package ar.edu.um.programacion2.turnos.reservation.domain.model;

import ar.edu.um.programacion2.turnos.reservation.domain.model.ProfessionalAgenda.AgendaProfessional;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Los turnos libres de un profesional en un rango, en el momento de la consulta (CU-03). Es una foto: turnos no la guarda
 * y la cátedra vuelve a validar al crear el hold.
 * <p>
 * {@code days} trae <strong>todos</strong> los días del rango, en orden, también los que no tienen turnos: la app arma la
 * semana sin calcular fechas y un día vacío se distingue de un día que no se pidió.
 */
public record ProfessionalAvailability(AgendaProfessional professional, LocalDate from, LocalDate to, ZoneId zone, List<DailyAvailability> days) {
    public ProfessionalAvailability {
        Objects.requireNonNull(professional, "Falta el profesional");
        Objects.requireNonNull(from, "Falta la fecha desde");
        Objects.requireNonNull(to, "Falta la fecha hasta");
        Objects.requireNonNull(zone, "Falta la zona horaria de atención");
        days = List.copyOf(Objects.requireNonNull(days, "Faltan los días"));
    }

    /** Agrupa los turnos libres por día. Un turno fuera del rango es un error de programación. */
    public static ProfessionalAvailability of(
        AgendaProfessional professional,
        LocalDate from,
        LocalDate to,
        ZoneId zone,
        List<AppointmentSlot> freeSlots
    ) {
        Objects.requireNonNull(freeSlots, "Faltan los turnos libres");
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("La fecha desde no puede ser posterior a la fecha hasta");
        }
        if (freeSlots.stream().anyMatch(slot -> slot.date().isBefore(from) || slot.date().isAfter(to))) {
            throw new IllegalArgumentException("Hay un turno fuera del rango consultado");
        }
        List<DailyAvailability> days = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            LocalDate day = date;
            days.add(
                new DailyAvailability(
                    day,
                    freeSlots.stream().filter(slot -> slot.date().equals(day)).sorted(Comparator.comparing(AppointmentSlot::startTime)).toList()
                )
            );
        }
        return new ProfessionalAvailability(professional, from, to, zone, days);
    }
}
