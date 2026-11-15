package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Genera los turnos de una agenda vigente (CU-03 paso 5). CU-04 usa el mismo generador para validar el turno pedido (paso
 * 6): así "turno ofrecido" y "turno reservable" no pueden ser dos reglas distintas.
 * <p>
 * Para cada fecha y cada horario de ese día de la semana, un turno cada {@code slotDurationMinutes} desde
 * {@code startTime}, siempre que termine antes o justo en {@code endTime}.
 * <p>
 * <strong>La cuenta se hace con los nanosegundos del día, en {@code long}, y no con {@code LocalTime.plusMinutes}</strong>,
 * que da la vuelta a medianoche sin avisar: con un horario de 23:00 a 23:59 y turnos de 30 minutos, {@code 23:30 + 30 min}
 * es {@code 00:00}, que es "antes" de las 23:59. El turno falso de 23:30 entraría y el bucle no terminaría nunca. Con
 * números, {@code 23:30 + 30 min} es 86.400 segundos, mayor que el fin: no entra y el bucle termina.
 * <p>
 * Lo que no genera turnos (son datos, no errores: el contrato no garantiza nada de esto):
 * <ul>
 * <li>una duración menor o igual a cero, o de más de un día;</li>
 * <li>un horario con {@code startTime >= endTime} (intervalo vacío, como en el #27 del catálogo);</li>
 * <li>un horario cuyo {@code startTime} no es un minuto entero: la cátedra recibe la hora del hold como {@code HH:mm}
 * (contrato §9), así que un turno con segundos no se podría reservar, y CU-03 no ofrece lo que CU-04 no puede
 * reservar.</li>
 * </ul>
 * <strong>Horarios que se superponen:</strong> la cátedra identifica un hold por profesional, fecha y hora de inicio, así
 * que dos horarios que generan el mismo inicio son el mismo turno. Queda uno solo: el del horario que empieza antes y, a
 * igualdad, el de menor {@code id}. Dos turnos con inicios distintos que se pisan se ofrecen los dos: no se sabe cómo los
 * trata la cátedra, que tiene la última palabra al crear el hold.
 */
public final class SlotGenerator {

    private static final long NANOS_PER_MINUTE = 60_000_000_000L;
    private static final int MINUTES_PER_DAY = 24 * 60;

    private static final Comparator<WeeklySchedule> EARLIEST_THEN_LOWEST_ID = Comparator.comparing(WeeklySchedule::startTime).thenComparingLong(
        WeeklySchedule::id
    );

    private SlotGenerator() {}

    /**
     * Los turnos de la agenda entre dos fechas, las dos incluidas, ordenados por fecha y hora de inicio.
     *
     * @throws IllegalArgumentException si {@code from} es posterior a {@code to}
     */
    public static List<ScheduledSlot> generate(ProfessionalAgenda agenda, LocalDate from, LocalDate to) {
        Objects.requireNonNull(agenda, "Falta la agenda");
        Objects.requireNonNull(from, "Falta la fecha desde");
        Objects.requireNonNull(to, "Falta la fecha hasta");
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("La fecha desde no puede ser posterior a la fecha hasta");
        }
        List<ScheduledSlot> slots = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            slots.addAll(slotsOf(agenda, date));
        }
        return List.copyOf(slots);
    }

    /** El turno de la agenda que empieza ese día a esa hora exacta, si existe (CU-04 paso 6). */
    public static Optional<ScheduledSlot> find(ProfessionalAgenda agenda, LocalDate date, LocalTime startTime) {
        Objects.requireNonNull(startTime, "Falta la hora de inicio");
        return generate(agenda, date, date).stream().filter(scheduled -> scheduled.slot().startTime().equals(startTime)).findFirst();
    }

    private static List<ScheduledSlot> slotsOf(ProfessionalAgenda agenda, LocalDate date) {
        // Por hora de inicio: ordena el resultado y deja un solo turno por inicio (el primero que llega).
        Map<LocalTime, ScheduledSlot> byStartTime = new TreeMap<>();
        agenda
            .weeklySchedules()
            .stream()
            .filter(schedule -> schedule.dayOfWeek() == date.getDayOfWeek())
            .sorted(EARLIEST_THEN_LOWEST_ID)
            .forEach(schedule -> addSlots(byStartTime, schedule, agenda.professional().id(), date));
        return List.copyOf(byStartTime.values());
    }

    private static void addSlots(Map<LocalTime, ScheduledSlot> byStartTime, WeeklySchedule schedule, long professionalId, LocalDate date) {
        int minutes = schedule.slotDurationMinutes();
        long start = schedule.startTime().toNanoOfDay();
        long end = schedule.endTime().toNanoOfDay();
        if (minutes <= 0 || minutes > MINUTES_PER_DAY || start >= end || start % NANOS_PER_MINUTE != 0) {
            return;
        }
        long step = minutes * NANOS_PER_MINUTE;
        // slotStart + step nunca supera end, y end es menor que un día: los dos LocalTime son válidos y el bucle termina.
        for (long slotStart = start; slotStart + step <= end; slotStart += step) {
            LocalTime startTime = LocalTime.ofNanoOfDay(slotStart);
            AppointmentSlot slot = new AppointmentSlot(professionalId, date, startTime, LocalTime.ofNanoOfDay(slotStart + step));
            byStartTime.putIfAbsent(startTime, new ScheduledSlot(slot, minutes));
        }
    }
}
