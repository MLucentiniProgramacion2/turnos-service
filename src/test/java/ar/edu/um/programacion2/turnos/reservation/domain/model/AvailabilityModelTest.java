package ar.edu.um.programacion2.turnos.reservation.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.um.programacion2.turnos.reservation.domain.model.ProfessionalAgenda.AgendaCategory;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ProfessionalAgenda.AgendaProfessional;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Las piezas de la disponibilidad (CU-03): qué turno está ocupado, cuál ya empezó y cómo se agrupan por día. */
class AvailabilityModelTest {

    private static final LocalDate LUNES = LocalDate.parse("2026-12-14");
    private static final ZoneId MENDOZA = ZoneId.of("America/Argentina/Mendoza");
    private static final AgendaProfessional PROFESIONAL = new AgendaProfessional(202, "Ana", "Gomez", true, new AgendaCategory(1, "Clínica", true));

    @ParameterizedTest
    @EnumSource(OccupancyStatus.class)
    void unTurnoEstaOcupadoSiHayUnaOcupacionConSuFechaYSuInicioSeaRetenidaOConfirmada(OccupancyStatus estado) {
        Instant vence = estado == OccupancyStatus.HELD ? Instant.parse("2026-12-10T14:44:50Z") : null;
        OccupancyReport ocupaciones = ocupaciones(new Occupancy(LUNES, LocalTime.parse("09:30"), estado, vence));

        assertThat(ocupaciones.isOccupied(turno(LUNES, "09:30"))).isTrue();
        assertThat(ocupaciones.isOccupied(turno(LUNES, "09:00"))).isFalse();
        assertThat(ocupaciones.isOccupied(turno(LUNES.plusDays(1), "09:30"))).isFalse();
    }

    @Test
    void laHoraDeLaOcupacionConSegundosEnCeroEsLaMismaQueSinSegundos() {
        // La cátedra manda "09:30:00" (contrato §8) y el turno se genera como 09:30.
        OccupancyReport ocupaciones = ocupaciones(new Occupancy(LUNES, LocalTime.parse("09:30:00"), OccupancyStatus.CONFIRMED, null));

        assertThat(ocupaciones.isOccupied(turno(LUNES, "09:30"))).isTrue();
    }

    @Test
    void unaOcupacionQueNoCoincideConElInicioDeUnTurnoNoLoOcupa() {
        OccupancyReport ocupaciones = ocupaciones(
            new Occupancy(LUNES, LocalTime.parse("09:15"), OccupancyStatus.CONFIRMED, null),
            new Occupancy(LUNES, LocalTime.parse("09:30:01"), OccupancyStatus.CONFIRMED, null)
        );

        assertThat(ocupaciones.isOccupied(turno(LUNES, "09:00"))).isFalse();
        assertThat(ocupaciones.isOccupied(turno(LUNES, "09:30"))).isFalse();
    }

    @Test
    void unTurnoYaEmpezoSiSuInicioEsAhoraOAntes() {
        AppointmentSlot turno = turno(LUNES, "09:30");

        assertThat(turno.hasStartedAt(LocalDateTime.parse("2026-12-14T09:29:59"))).isFalse();
        assertThat(turno.hasStartedAt(LocalDateTime.parse("2026-12-14T09:30:00"))).isTrue();
        assertThat(turno.hasStartedAt(LocalDateTime.parse("2026-12-14T09:30:01"))).isTrue();
        assertThat(turno.hasStartedAt(LocalDateTime.parse("2026-12-13T23:59:59"))).isFalse();
        assertThat(turno.hasStartedAt(LocalDateTime.parse("2026-12-15T00:00:00"))).isTrue();
    }

    @Test
    void laDisponibilidadTraeTodosLosDiasDelRangoTambienLosVacios() {
        ProfessionalAvailability disponibilidad = ProfessionalAvailability.of(
            PROFESIONAL,
            LUNES,
            LUNES.plusDays(2),
            MENDOZA,
            List.of(turno(LUNES.plusDays(2), "10:00"), turno(LUNES, "09:30"), turno(LUNES, "09:00"))
        );

        assertThat(disponibilidad.days()).extracting(DailyAvailability::date).containsExactly(LUNES, LUNES.plusDays(1), LUNES.plusDays(2));
        assertThat(disponibilidad.days().get(0).slots()).containsExactly(turno(LUNES, "09:00"), turno(LUNES, "09:30"));
        assertThat(disponibilidad.days().get(1).slots()).isEmpty();
        assertThat(disponibilidad.days().get(2).slots()).containsExactly(turno(LUNES.plusDays(2), "10:00"));
        assertThat(disponibilidad.zone()).isEqualTo(MENDOZA);
    }

    @Test
    void sinTurnosLibresIgualEstanTodosLosDias() {
        ProfessionalAvailability disponibilidad = ProfessionalAvailability.of(PROFESIONAL, LUNES, LUNES.plusDays(6), MENDOZA, List.of());

        assertThat(disponibilidad.days()).hasSize(7).allSatisfy(dia -> assertThat(dia.slots()).isEmpty());
    }

    @Test
    void unTurnoFueraDelRangoEsUnErrorDeProgramacion() {
        assertThatThrownBy(() -> ProfessionalAvailability.of(PROFESIONAL, LUNES, LUNES, MENDOZA, List.of(turno(LUNES.plusDays(1), "09:00")))
        ).isInstanceOf(IllegalArgumentException.class);
    }

    private static OccupancyReport ocupaciones(Occupancy... ocupaciones) {
        return new OccupancyReport(202, LUNES, LUNES.plusDays(6), List.of(ocupaciones));
    }

    private static AppointmentSlot turno(LocalDate fecha, String inicio) {
        LocalTime hora = LocalTime.parse(inicio);
        return new AppointmentSlot(202, fecha, hora, hora.plusMinutes(30));
    }
}
