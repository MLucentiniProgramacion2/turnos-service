package ar.edu.um.programacion2.turnos.reservation.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ar.edu.um.programacion2.turnos.reservation.domain.model.AppointmentSlot;
import ar.edu.um.programacion2.turnos.reservation.domain.model.AvailabilityPolicy;
import ar.edu.um.programacion2.turnos.reservation.domain.model.AvailabilityResult;
import ar.edu.um.programacion2.turnos.reservation.domain.model.DailyAvailability;
import ar.edu.um.programacion2.turnos.reservation.domain.model.Occupancy;
import ar.edu.um.programacion2.turnos.reservation.domain.model.OccupancyReport;
import ar.edu.um.programacion2.turnos.reservation.domain.model.OccupancyStatus;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ProfessionalAgenda;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ProfessionalAgenda.AgendaCategory;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ProfessionalAgenda.AgendaProfessional;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ProfessionalAvailability;
import ar.edu.um.programacion2.turnos.reservation.domain.model.RangeViolation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.WeeklySchedule;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatalogAccessDeniedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatalogInvalidResponseException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatalogUnavailableException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraAccessDeniedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraErrorCode;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraInvalidResponseException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraOutcomeUnknownException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraRejectedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraUnavailableException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.OccupancyProvider;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ProfessionalAgendaProvider;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;

/**
 * CU-03 con los puertos simulados y un reloj fijo: un test por flujo, con su número. "Ahora" es el lunes 14/12/2026 a las
 * 09:30 de Mendoza (12:30 UTC).
 */
class GetAvailabilityUseCaseImplTest {

    private static final long PROFESIONAL = 202;
    private static final String LOGIN = "ana.perez";
    private static final ZoneId MENDOZA = ZoneId.of("America/Argentina/Mendoza");
    private static final LocalDate HOY = LocalDate.parse("2026-12-14");
    private static final LocalDate MARTES = HOY.plusDays(1);
    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-12-14T12:30:00Z"), ZoneOffset.UTC);

    private final ProfessionalAgendaProvider catalogo = mock(ProfessionalAgendaProvider.class);
    private final OccupancyProvider catedra = mock(OccupancyProvider.class);
    private final GetAvailabilityUseCaseImpl casoDeUso = new GetAvailabilityUseCaseImpl(catalogo, catedra, new AvailabilityPolicy(MENDOZA, 60), RELOJ);

    @Test
    void cu03_principal_agendaDelCatalogoMenosOcupacionesDeLaCatedra() {
        hayAgenda(horario(MARTES.getDayOfWeek(), "09:00", "11:00", 30));
        hayOcupaciones(HOY, MARTES, new Occupancy(MARTES, LocalTime.parse("09:30:00"), OccupancyStatus.CONFIRMED, null));

        ProfessionalAvailability disponibilidad = disponible(casoDeUso.getAvailability(PROFESIONAL, HOY, MARTES, LOGIN));

        assertThat(disponibilidad.professional().id()).isEqualTo(PROFESIONAL);
        assertThat(disponibilidad.zone()).isEqualTo(MENDOZA);
        assertThat(disponibilidad.days()).extracting(DailyAvailability::date).containsExactly(HOY, MARTES);
        assertThat(disponibilidad.days().get(0).slots()).isEmpty();
        assertThat(inicios(disponibilidad.days().get(1))).containsExactly("09:00", "10:00", "10:30");
        assertThat(disponibilidad.days().get(1).slots().getFirst()).isEqualTo(
            new AppointmentSlot(PROFESIONAL, MARTES, LocalTime.parse("09:00"), LocalTime.parse("09:30"))
        );
        // Primero el catálogo, con el login para su log; después la cátedra, una sola vez para todo el rango.
        InOrder orden = inOrder(catalogo, catedra);
        orden.verify(catalogo).fetchAgenda(PROFESIONAL, LOGIN);
        orden.verify(catedra).fetchOccupancies(PROFESIONAL, HOY, MARTES);
        orden.verifyNoMoreInteractions();
    }

    @ParameterizedTest(name = "{0} a {1}: {2}")
    @CsvSource(
        {
            "2026-12-16, 2026-12-15, FROM_AFTER_TO",
            "2026-12-14, 2026-12-21, TOO_LONG",
            "2026-12-13, 2026-12-14, IN_THE_PAST",
            "2027-02-10, 2027-02-13, BEYOND_HORIZON",
        }
    )
    void cu03_2b_unRangoInvalidoNoLlamaANadie(LocalDate desde, LocalDate hasta, RangeViolation regla) {
        assertThat(casoDeUso.getAvailability(PROFESIONAL, desde, hasta, LOGIN)).isEqualTo(new AvailabilityResult.InvalidRange(regla));

        verifyNoInteractions(catalogo, catedra);
    }

    @Test
    void cu03_2_losBordesDelRangoSonInclusivos() {
        // 7 días (to = from + 6) y el último día del horizonte (hoy + 60 = 12/02/2027) se pueden consultar.
        hayAgenda();

        assertThat(casoDeUso.getAvailability(PROFESIONAL, HOY, HOY.plusDays(6), LOGIN)).isInstanceOf(AvailabilityResult.Available.class);
        assertThat(casoDeUso.getAvailability(PROFESIONAL, HOY.plusDays(60), HOY.plusDays(60), LOGIN)).isInstanceOf(AvailabilityResult.Available.class);
    }

    @Test
    void cu03_2_hoySeCalculaEnLaZonaDeAtencionYNoEnUtc() {
        // 02:30 UTC del martes 15 todavía es lunes 14 a las 23:30 en Mendoza: pedir el lunes no es pedir el pasado.
        Clock casiMedianoche = Clock.fixed(Instant.parse("2026-12-15T02:30:00Z"), ZoneOffset.UTC);
        GetAvailabilityUseCaseImpl deNoche = new GetAvailabilityUseCaseImpl(catalogo, catedra, new AvailabilityPolicy(MENDOZA, 60), casiMedianoche);
        hayAgenda();

        assertThat(deNoche.getAvailability(PROFESIONAL, HOY, HOY, LOGIN)).isInstanceOf(AvailabilityResult.Available.class);
    }

    @Test
    void cu03_3a_conElCatalogoCaidoNoSeLlamaALaCatedraYSeInformaCuandoReintentar() {
        when(catalogo.fetchAgenda(PROFESIONAL, LOGIN)).thenThrow(new CatalogUnavailableException("503", Duration.ofSeconds(60)));

        assertThat(casoDeUso.getAvailability(PROFESIONAL, HOY, MARTES, LOGIN)).isEqualTo(
            new AvailabilityResult.CatalogUnavailable(Duration.ofSeconds(60))
        );
        verifyNoInteractions(catedra);
    }

    @Test
    void cu03_3a_siElCatalogoNoRespondeNoHayPlazoParaReintentar() {
        when(catalogo.fetchAgenda(PROFESIONAL, LOGIN)).thenThrow(new CatalogUnavailableException("sin respuesta", new RuntimeException()));

        assertThat(casoDeUso.getAvailability(PROFESIONAL, HOY, MARTES, LOGIN)).isEqualTo(new AvailabilityResult.CatalogUnavailable(null));
        verifyNoInteractions(catedra);
    }

    @Test
    void cu03_3a_siElCatalogoRechazaElTokenDeServicioElUsuarioVeLoMismoQueConElCatalogoCaido() {
        when(catalogo.fetchAgenda(PROFESIONAL, LOGIN)).thenThrow(new CatalogAccessDeniedException("403"));

        assertThat(casoDeUso.getAvailability(PROFESIONAL, HOY, MARTES, LOGIN)).isEqualTo(new AvailabilityResult.CatalogUnavailable(null));
        verifyNoInteractions(catedra);
    }

    @Test
    void cu03_3_unaRespuestaDelCatalogoFueraDeContratoNoLlamaALaCatedra() {
        when(catalogo.fetchAgenda(PROFESIONAL, LOGIN)).thenThrow(new CatalogInvalidResponseException("JSON roto"));

        assertThat(casoDeUso.getAvailability(PROFESIONAL, HOY, MARTES, LOGIN)).isEqualTo(new AvailabilityResult.UpstreamInvalidResponse());
        verifyNoInteractions(catedra);
    }

    @Test
    void cu03_4a_siElProfesionalNoExisteNoSeLlamaALaCatedra() {
        when(catalogo.fetchAgenda(PROFESIONAL, LOGIN)).thenReturn(Optional.empty());

        assertThat(casoDeUso.getAvailability(PROFESIONAL, HOY, MARTES, LOGIN)).isEqualTo(new AvailabilityResult.ProfessionalNotFound());
        verifyNoInteractions(catedra);
    }

    @ParameterizedTest
    @CsvSource({ "0", "-5" })
    void cu03_4a_unIdQueNoPuedeExistirNoLlamaANadie(long id) {
        assertThat(casoDeUso.getAvailability(id, HOY, MARTES, LOGIN)).isEqualTo(new AvailabilityResult.ProfessionalNotFound());

        verifyNoInteractions(catalogo, catedra);
    }

    @Test
    void cu03_4b_siElProfesionalEstaDeshabilitadoNoSeOfrecenTurnosNiSeLlamaALaCatedra() {
        when(catalogo.fetchAgenda(PROFESIONAL, LOGIN)).thenReturn(Optional.of(new ProfessionalAgenda(7, profesional(false), List.of())));

        assertThat(casoDeUso.getAvailability(PROFESIONAL, HOY, MARTES, LOGIN)).isEqualTo(new AvailabilityResult.ProfessionalDisabled());
        verifyNoInteractions(catedra);
    }

    @Test
    void cu03_5a_sinHorariosEnLosDiasDelRangoLaRespuestaEsVaciaYNoSeLlamaALaCatedra() {
        hayAgenda(horario(DayOfWeek.FRIDAY, "09:00", "11:00", 30));

        ProfessionalAvailability disponibilidad = disponible(casoDeUso.getAvailability(PROFESIONAL, HOY, MARTES, LOGIN));

        assertThat(disponibilidad.days()).hasSize(2).allSatisfy(dia -> assertThat(dia.slots()).isEmpty());
        verifyNoInteractions(catedra);
    }

    @Test
    void cu03_6a_siLaCatedraNoRespondeNoSeReintenta() {
        hayAgenda(horario(MARTES.getDayOfWeek(), "09:00", "11:00", 30));
        when(catedra.fetchOccupancies(anyLong(), any(), any())).thenThrow(new CatedraUnavailableException("timeout"));

        assertThat(casoDeUso.getAvailability(PROFESIONAL, HOY, MARTES, LOGIN)).isEqualTo(new AvailabilityResult.CatedraUnavailable());
        verify(catedra).fetchOccupancies(PROFESIONAL, HOY, MARTES);
    }

    @Test
    void cu03_6a_unResultadoDesconocidoEnUnaConsultaTambienEsNoDisponible() {
        hayAgenda(horario(MARTES.getDayOfWeek(), "09:00", "11:00", 30));
        when(catedra.fetchOccupancies(anyLong(), any(), any())).thenThrow(new CatedraOutcomeUnknownException("no debería pasar"));

        assertThat(casoDeUso.getAvailability(PROFESIONAL, HOY, MARTES, LOGIN)).isEqualTo(new AvailabilityResult.CatedraUnavailable());
    }

    @Test
    void cu03_6b_siLaCatedraRechazaUnRangoYaValidadoEsUnErrorNuestro() {
        hayAgenda(horario(MARTES.getDayOfWeek(), "09:00", "11:00", 30));
        when(catedra.fetchOccupancies(anyLong(), any(), any())).thenThrow(
            new CatedraRejectedException(400, CatedraErrorCode.INVALID_DATE_RANGE, "400 INVALID_DATE_RANGE")
        );

        assertThat(casoDeUso.getAvailability(PROFESIONAL, HOY, MARTES, LOGIN)).isEqualTo(new AvailabilityResult.InternalError());
    }

    @Test
    void cu03_6c_siLaCatedraRechazaLaCredencialTecnicaElUsuarioVeUnNoDisponible() {
        hayAgenda(horario(MARTES.getDayOfWeek(), "09:00", "11:00", 30));
        when(catedra.fetchOccupancies(anyLong(), any(), any())).thenThrow(new CatedraAccessDeniedException("401"));

        assertThat(casoDeUso.getAvailability(PROFESIONAL, HOY, MARTES, LOGIN)).isEqualTo(new AvailabilityResult.CatedraUnavailable());
    }

    @Test
    void cu03_6_unaRespuestaDeLaCatedraFueraDeContratoSeInformaComoTal() {
        hayAgenda(horario(MARTES.getDayOfWeek(), "09:00", "11:00", 30));
        when(catedra.fetchOccupancies(anyLong(), any(), any())).thenThrow(new CatedraInvalidResponseException("JSON roto"));

        assertThat(casoDeUso.getAvailability(PROFESIONAL, HOY, MARTES, LOGIN)).isEqualTo(new AvailabilityResult.UpstreamInvalidResponse());
    }

    @Test
    void cu03_7_seDescartanLosRetenidosYLosConfirmadosYSeIgnoraLaOcupacionQueNoCoincide() {
        hayAgenda(horario(MARTES.getDayOfWeek(), "09:00", "11:00", 30));
        hayOcupaciones(
            MARTES,
            MARTES,
            new Occupancy(MARTES, LocalTime.parse("09:00"), OccupancyStatus.HELD, Instant.parse("2026-12-14T12:40:00Z")),
            new Occupancy(MARTES, LocalTime.parse("10:00:00"), OccupancyStatus.CONFIRMED, null),
            new Occupancy(MARTES, LocalTime.parse("10:45"), OccupancyStatus.CONFIRMED, null)
        );

        ProfessionalAvailability disponibilidad = disponible(casoDeUso.getAvailability(PROFESIONAL, MARTES, MARTES, LOGIN));

        assertThat(inicios(disponibilidad.days().getFirst())).containsExactly("09:30", "10:30");
    }

    @Test
    void cu03_7a_conTodosLosTurnosOcupadosLaRespuestaEsVacia() {
        hayAgenda(horario(MARTES.getDayOfWeek(), "09:00", "10:00", 30));
        hayOcupaciones(
            MARTES,
            MARTES,
            new Occupancy(MARTES, LocalTime.parse("09:00"), OccupancyStatus.CONFIRMED, null),
            new Occupancy(MARTES, LocalTime.parse("09:30"), OccupancyStatus.HELD, Instant.parse("2026-12-14T12:40:00Z"))
        );

        ProfessionalAvailability disponibilidad = disponible(casoDeUso.getAvailability(PROFESIONAL, MARTES, MARTES, LOGIN));

        assertThat(disponibilidad.days()).singleElement().satisfies(dia -> assertThat(dia.slots()).isEmpty());
    }

    @Test
    void cu03_8_deHoySeDescartanLosTurnosQueYaEmpezaronIncluidoElQueEmpiezaAhora() {
        // Son las 09:30: el de 09:00 y el de 09:30 ya empezaron; el de 10:00 no. Los de mañana no se tocan.
        hayAgenda(horario(HOY.getDayOfWeek(), "09:00", "10:30", 30), horario(MARTES.getDayOfWeek(), "09:00", "10:00", 30));
        hayOcupaciones(HOY, MARTES);

        ProfessionalAvailability disponibilidad = disponible(casoDeUso.getAvailability(PROFESIONAL, HOY, MARTES, LOGIN));

        assertThat(inicios(disponibilidad.days().get(0))).containsExactly("10:00");
        assertThat(inicios(disponibilidad.days().get(1))).containsExactly("09:00", "09:30");
    }

    @Test
    void cu03_8_siTodosLosTurnosDelRangoYaEmpezaronNoSeLlamaALaCatedra() {
        hayAgenda(horario(HOY.getDayOfWeek(), "08:00", "09:30", 30));

        ProfessionalAvailability disponibilidad = disponible(casoDeUso.getAvailability(PROFESIONAL, HOY, HOY, LOGIN));

        assertThat(disponibilidad.days()).singleElement().satisfies(dia -> assertThat(dia.slots()).isEmpty());
        verifyNoInteractions(catedra);
    }

    private void hayAgenda(WeeklySchedule... horarios) {
        when(catalogo.fetchAgenda(PROFESIONAL, LOGIN)).thenReturn(Optional.of(new ProfessionalAgenda(7, profesional(true), List.of(horarios))));
    }

    private void hayOcupaciones(LocalDate desde, LocalDate hasta, Occupancy... ocupaciones) {
        when(catedra.fetchOccupancies(PROFESIONAL, desde, hasta)).thenReturn(new OccupancyReport(PROFESIONAL, desde, hasta, List.of(ocupaciones)));
    }

    private static AgendaProfessional profesional(boolean habilitado) {
        return new AgendaProfessional(PROFESIONAL, "Ana", "Gomez", habilitado, new AgendaCategory(1, "Clínica", true));
    }

    private static WeeklySchedule horario(DayOfWeek dia, String inicio, String fin, int minutos) {
        return new WeeklySchedule(1, dia, LocalTime.parse(inicio), LocalTime.parse(fin), minutos);
    }

    private static ProfessionalAvailability disponible(AvailabilityResult resultado) {
        assertThat(resultado).isInstanceOf(AvailabilityResult.Available.class);
        return ((AvailabilityResult.Available) resultado).availability();
    }

    private static List<String> inicios(DailyAvailability dia) {
        return dia.slots().stream().map(turno -> turno.startTime().toString()).toList();
    }
}
