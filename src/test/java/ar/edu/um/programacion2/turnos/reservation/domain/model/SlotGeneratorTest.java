package ar.edu.um.programacion2.turnos.reservation.domain.model;

import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.TUESDAY;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import ar.edu.um.programacion2.turnos.reservation.domain.model.ProfessionalAgenda.AgendaCategory;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ProfessionalAgenda.AgendaProfessional;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * El generador de turnos de CU-03 y CU-04. Los casos de un solo horario van en una tabla: horario, duración y los inicios
 * que se esperan. Cada test corre con un límite de tiempo, porque el error típico de este código es un bucle que no
 * termina (la vuelta a medianoche de {@code LocalTime.plusMinutes}).
 */
class SlotGeneratorTest {

    private static final long PROFESIONAL = 202;
    // 2026-12-14 es lunes.
    private static final LocalDate LUNES = LocalDate.parse("2026-12-14");

    static Stream<Arguments> unHorario() {
        return Stream.of(
            Arguments.of("tres horas de a 30 minutos", "09:00", "12:00", 30, List.of("09:00", "09:30", "10:00", "10:30", "11:00", "11:30")),
            Arguments.of("el último turno termina justo en el fin", "09:00", "10:00", 30, List.of("09:00", "09:30")),
            Arguments.of("el resto que no alcanza para un turno no se ofrece", "09:00", "10:15", 30, List.of("09:00", "09:30")),
            Arguments.of("un minuto menos que un turno no genera nada", "09:00", "09:29", 30, List.of()),
            Arguments.of("un solo turno que ocupa todo el horario", "09:00", "09:45", 45, List.of("09:00")),
            Arguments.of("23:00 a 23:59 de a 30: el de 23:30 terminaría a medianoche y no entra", "23:00", "23:59", 30, List.of("23:00")),
            Arguments.of("23:00 a 23:59:59 de a 30: tampoco entra el de 23:30", "23:00", "23:59:59", 30, List.of("23:00")),
            Arguments.of("23:30 a 23:59 de a 30: ninguno", "23:30", "23:59", 30, List.of()),
            Arguments.of("de a un minuto hasta el último del día", "23:57", "23:59", 1, List.of("23:57", "23:58")),
            Arguments.of("todo el día de a 12 horas", "00:00", "23:59:59", 720, List.of("00:00")),
            Arguments.of("duración cero", "09:00", "12:00", 0, List.of()),
            Arguments.of("duración negativa", "09:00", "12:00", -30, List.of()),
            Arguments.of("duración de más de un día", "00:00", "23:59", 1441, List.of()),
            Arguments.of("la duración más grande que entra en un int", "00:00", "23:59", Integer.MAX_VALUE, List.of()),
            Arguments.of("inicio igual al fin", "09:00", "09:00", 30, List.of()),
            Arguments.of("inicio posterior al fin", "12:00", "09:00", 30, List.of()),
            Arguments.of("inicio con segundos: no se podría reservar con HH:mm", "09:00:30", "12:00", 30, List.of()),
            Arguments.of("fin con segundos: no cambia los inicios", "09:00", "10:00:30", 30, List.of("09:00", "09:30"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unHorario")
    void unHorarioGeneraSusTurnos(String caso, String inicio, String fin, int minutos, List<String> iniciosEsperados) {
        ProfessionalAgenda agenda = agenda(horario(1, MONDAY, inicio, fin, minutos));

        List<ScheduledSlot> turnos = assertTimeoutPreemptively(ofSeconds(5), () -> SlotGenerator.generate(agenda, LUNES, LUNES));

        assertThat(turnos).extracting(turno -> turno.slot().startTime()).containsExactlyElementsOf(horas(iniciosEsperados));
        assertThat(turnos).allSatisfy(turno -> {
            assertThat(turno.slot().professionalId()).isEqualTo(PROFESIONAL);
            assertThat(turno.slot().date()).isEqualTo(LUNES);
            assertThat(turno.slot().endTime()).isEqualTo(LocalTime.ofNanoOfDay(turno.slot().startTime().toNanoOfDay() + minutos * 60_000_000_000L));
            assertThat(turno.slotDurationMinutes()).isEqualTo(minutos);
        });
    }

    @Test
    void soloSeGeneranLosDiasDeLaSemanaDelHorario() {
        ProfessionalAgenda agenda = agenda(horario(1, TUESDAY, "09:00", "10:00", 30));

        assertThat(SlotGenerator.generate(agenda, LUNES, LUNES)).isEmpty();
        assertThat(SlotGenerator.generate(agenda, LUNES, LUNES.plusDays(1)))
            .extracting(turno -> turno.slot().date())
            .containsExactly(LUNES.plusDays(1), LUNES.plusDays(1));
    }

    @Test
    void unaSemanaQueCruzaDeMesSaleOrdenadaPorFechaYHora() {
        // Del sábado 28/11 al viernes 4/12: el lunes es 30/11 y el martes 1/12.
        ProfessionalAgenda agenda = agenda(
            horario(2, TUESDAY, "08:00", "09:00", 30),
            horario(3, MONDAY, "15:00", "16:00", 60),
            horario(1, MONDAY, "09:00", "10:00", 30)
        );

        List<ScheduledSlot> turnos = SlotGenerator.generate(agenda, LocalDate.parse("2026-11-28"), LocalDate.parse("2026-12-04"));

        assertThat(turnos)
            .extracting(turno -> turno.slot().date() + " " + turno.slot().startTime())
            .containsExactly("2026-11-30 09:00", "2026-11-30 09:30", "2026-11-30 15:00", "2026-12-01 08:00", "2026-12-01 08:30");
    }

    @Test
    void dosHorariosConElMismoInicioDejanUnSoloTurnoElDelQueEmpiezaAntes() {
        // El 09:30 lo generan los dos. Gana el horario 7 (empieza 09:00) aunque su id sea mayor: fin 10:00 y 30 minutos.
        ProfessionalAgenda agenda = agenda(horario(3, MONDAY, "09:30", "11:00", 45), horario(7, MONDAY, "09:00", "10:00", 30));

        List<ScheduledSlot> turnos = SlotGenerator.generate(agenda, LUNES, LUNES);

        assertThat(turnos).extracting(turno -> turno.slot().startTime()).containsExactlyElementsOf(horas(List.of("09:00", "09:30", "10:15")));
        ScheduledSlot repetido = turnos.get(1);
        assertThat(repetido.slot().endTime()).isEqualTo(LocalTime.parse("10:00"));
        assertThat(repetido.slotDurationMinutes()).isEqualTo(30);
    }

    @Test
    void dosHorariosQueEmpiezanALaMismaHoraGanaElDeMenorId() {
        ProfessionalAgenda agenda = agenda(horario(9, MONDAY, "09:00", "10:00", 20), horario(4, MONDAY, "09:00", "10:00", 60));

        List<ScheduledSlot> turnos = SlotGenerator.generate(agenda, LUNES, LUNES);

        // 09:00 es del horario 4 (60 minutos); 09:20 y 09:40 solo los genera el 9.
        assertThat(turnos).extracting(turno -> turno.slot().startTime()).containsExactlyElementsOf(horas(List.of("09:00", "09:20", "09:40")));
        assertThat(turnos.getFirst().slotDurationMinutes()).isEqualTo(60);
        assertThat(turnos.getFirst().slot().endTime()).isEqualTo(LocalTime.parse("10:00"));
    }

    @Test
    void dosTurnosConIniciosDistintosQueSePisanSeOfrecenLosDos() {
        ProfessionalAgenda agenda = agenda(horario(1, MONDAY, "09:00", "10:00", 60), horario(2, MONDAY, "09:30", "10:30", 60));

        assertThat(SlotGenerator.generate(agenda, LUNES, LUNES))
            .extracting(turno -> turno.slot().startTime())
            .containsExactlyElementsOf(horas(List.of("09:00", "09:30")));
    }

    @Test
    void unaAgendaSinHorariosNoGeneraNada() {
        assertThat(SlotGenerator.generate(agenda(), LUNES, LUNES.plusDays(6))).isEmpty();
    }

    @Test
    void elRangoAlRevesEsUnErrorDeProgramacion() {
        assertThatThrownBy(() -> SlotGenerator.generate(agenda(), LUNES, LUNES.minusDays(1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buscarUnTurnoDevuelveElQueEmpiezaEseDiaAEsaHoraExacta() {
        ProfessionalAgenda agenda = agenda(horario(1, MONDAY, "09:00", "10:30", 45));

        assertThat(SlotGenerator.find(agenda, LUNES, LocalTime.parse("09:45")))
            .get()
            .satisfies(turno -> {
                assertThat(turno.slot()).isEqualTo(new AppointmentSlot(PROFESIONAL, LUNES, LocalTime.parse("09:45"), LocalTime.parse("10:30")));
                assertThat(turno.slotDurationMinutes()).isEqualTo(45);
            });
    }

    static Stream<Arguments> turnosQueNoExisten() {
        return Stream.of(
            Arguments.of("no alineado a la duración", LUNES, "09:30"),
            Arguments.of("antes del horario", LUNES, "08:15"),
            Arguments.of("el que terminaría después del fin", LUNES, "10:30"),
            Arguments.of("otro día de la semana", LUNES.plusDays(1), "09:00"),
            Arguments.of("con segundos", LUNES, "09:00:01"),
            Arguments.of("con fracciones de segundo", LUNES, "09:00:00.5")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("turnosQueNoExisten")
    void buscarUnTurnoQueNoEsDeLaAgendaDaVacio(String caso, LocalDate fecha, String hora) {
        ProfessionalAgenda agenda = agenda(horario(1, MONDAY, "09:00", "10:30", 45));

        assertThat(SlotGenerator.find(agenda, fecha, LocalTime.parse(hora))).isEmpty();
    }

    private static ProfessionalAgenda agenda(WeeklySchedule... horarios) {
        return new ProfessionalAgenda(
            7,
            new AgendaProfessional(PROFESIONAL, "Ana", "Gomez", true, new AgendaCategory(1, "Clínica", true)),
            List.of(horarios)
        );
    }

    private static WeeklySchedule horario(long id, DayOfWeek dia, String inicio, String fin, int minutos) {
        return new WeeklySchedule(id, dia, LocalTime.parse(inicio), LocalTime.parse(fin), minutos);
    }

    private static List<LocalTime> horas(List<String> horas) {
        return horas.stream().map(LocalTime::parse).toList();
    }
}
