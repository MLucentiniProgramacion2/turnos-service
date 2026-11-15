package ar.edu.um.programacion2.turnos.reservation.application.usecases;

import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.CONFIRMED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.CREATING_HOLD;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.EXPIRED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.FAILED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.HOLD_CREATED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.PHONE_REQUESTED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.WAITING_FOR_PHONE_REQUEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ar.edu.um.programacion2.turnos.reservation.domain.model.AppointmentSlot;
import ar.edu.um.programacion2.turnos.reservation.domain.model.AvailabilityPolicy;
import ar.edu.um.programacion2.turnos.reservation.domain.model.Cancellation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraHold;
import ar.edu.um.programacion2.turnos.reservation.domain.model.Closing;
import ar.edu.um.programacion2.turnos.reservation.domain.model.Confirmation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.HoldConfirmation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PatientName;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PhoneNumber;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PhoneRejection;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PhoneRequest;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ProcessOwner;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ProfessionalAgenda;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ProfessionalAgenda.AgendaCategory;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ProfessionalAgenda.AgendaProfessional;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ProfessionalSnapshot;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessId;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.programacion2.turnos.reservation.domain.model.StartReservationCommand;
import ar.edu.um.programacion2.turnos.reservation.domain.model.StartReservationResult;
import ar.edu.um.programacion2.turnos.reservation.domain.model.UserProfile;
import ar.edu.um.programacion2.turnos.reservation.domain.model.WeeklySchedule;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatalogAccessDeniedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatalogInvalidResponseException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatalogUnavailableException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraAccessDeniedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraErrorCode;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraInvalidResponseException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraOutcomeUnknownException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraRejectedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraUnavailableException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.HoldGateway;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.IntermediateProcessConflictException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ProfessionalAgendaProvider;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.UserProfileProvider;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * CU-04 con los puertos simulados y un reloj fijo: un test por flujo alternativo, con su número. "Ahora" es el lunes
 * 14/12/2026 a las 09:30 de Mendoza; el turno pedido es el martes 15 a las 10:00, de un horario de 09:00 a 11:00 cada 30
 * minutos.
 * <p>
 * Después de cada test se comprueba que ningún pedido al catálogo o a la cátedra se hizo adentro de una transacción.
 */
class StartReservationUseCaseImplTest {

    private static final long ANA = 42;
    private static final long PROFESIONAL = 202;
    private static final ZoneId MENDOZA = ZoneId.of("America/Argentina/Mendoza");
    private static final Instant AHORA = Instant.parse("2026-12-14T12:30:00Z");
    private static final LocalDate HOY = LocalDate.parse("2026-12-14");
    private static final LocalDate MARTES = HOY.plusDays(1);
    private static final LocalTime DIEZ = LocalTime.parse("10:00");
    private static final Instant VENCE = Instant.parse("2026-12-14T12:40:00Z");
    private static final Instant VENCE_CONFIRM = Instant.parse("2026-12-14T12:40:05Z");
    private static final CatedraHold HOLD = new CatedraHold("hold-6c0c2f41", "process-a92b47d0", VENCE);
    private static final ReservationProcessId ID = new ReservationProcessId(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private static final StartReservationCommand PEDIDO = new StartReservationCommand(ANA, "ana.token", PROFESIONAL, MARTES, DIEZ);

    private final FakeTransactions transacciones = new FakeTransactions();
    private final InMemoryReservationProcessRepository repositorio = new InMemoryReservationProcessRepository(transacciones);
    private final UserProfileProvider perfiles = mock(UserProfileProvider.class);
    private final ProfessionalAgendaProvider catalogo = mock(ProfessionalAgendaProvider.class);
    private final HoldGateway catedra = mock(HoldGateway.class);
    private final List<String> pedidosAdentroDeUnaTransaccion = new ArrayList<>();

    private final StartReservationUseCaseImpl casoDeUso = new StartReservationUseCaseImpl(
        repositorio,
        perfiles,
        catalogo,
        catedra,
        new ReservationProcessUpdater(repositorio, transacciones),
        () -> ID,
        new AvailabilityPolicy(MENDOZA, 60),
        Clock.fixed(AHORA, ZoneOffset.UTC),
        transacciones
    );

    @BeforeEach
    void caminoFeliz() {
        when(perfiles.findById(ANA)).thenAnswer(invocation -> {
            transacciones.requireActive();
            return Optional.of(new UserProfile(ANA, "ana.perez", "Ana", "Pérez"));
        });
        when(catalogo.fetchAgenda(anyLong(), any())).thenAnswer(invocation -> afuera("catálogo", Optional.of(agenda(true))));
        when(catedra.createHold(any(), anyString())).thenAnswer(invocation -> afuera("hold", HOLD));
        when(catedra.confirmHold(anyString(), anyString(), any())).thenAnswer(invocation ->
            afuera("confirm", new HoldConfirmation("process-a92b47d0", VENCE_CONFIRM))
        );
    }

    @AfterEach
    void ningunPedidoSeHizoAdentroDeUnaTransaccion() {
        assertThat(pedidosAdentroDeUnaTransaccion).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Flujo principal
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void cu04_principal_guardaAntesDeCadaLlamadaYTerminaEsperandoElPedidoDeTelefono() {
        StartReservationResult resultado = casoDeUso.start(PEDIDO);

        ReservationProcess proceso = iniciado(resultado);
        assertThat(proceso.getId()).isEqualTo(ID);
        assertThat(proceso.getStatus()).isEqualTo(WAITING_FOR_PHONE_REQUEST);
        assertThat(proceso.getOwner()).isEqualTo(new ProcessOwner(ANA, "ana.perez"));
        assertThat(proceso.getSlot()).isEqualTo(new AppointmentSlot(PROFESIONAL, MARTES, DIEZ, LocalTime.parse("10:30")));
        assertThat(proceso.getProfessional()).isEqualTo(new ProfessionalSnapshot("Ana", "Gomez", "Clínica", 30));
        assertThat(proceso.getPatient()).isEqualTo(new PatientName("Ana", "Pérez"));
        assertThat(proceso.getHold()).contains(new CatedraHold("hold-6c0c2f41", "process-a92b47d0", VENCE_CONFIRM));
        assertThat(proceso.getCreatedAt()).isEqualTo(AHORA);
        assertThat(repositorio.get(ID).getStatus()).isEqualTo(WAITING_FOR_PHONE_REQUEST);

        // Cada guardado es anterior a la llamada siguiente: CREATING_HOLD antes del hold, HOLD_CREATED antes del confirm.
        assertThat(repositorio.writes()).containsExactly("insert CREATING_HOLD", "save HOLD_CREATED", "save WAITING_FOR_PHONE_REQUEST");
        InOrder orden = inOrder(catalogo, catedra);
        orden.verify(catalogo).fetchAgenda(PROFESIONAL, "ana.token");
        orden.verify(catedra).createHold(new AppointmentSlot(PROFESIONAL, MARTES, DIEZ, LocalTime.parse("10:30")), "user-42");
        orden.verify(catedra).confirmHold("hold-6c0c2f41", "user-42", new PatientName("Ana", "Pérez"));
        orden.verifyNoMoreInteractions();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 2b: validación
    // ---------------------------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} {1}: {2}")
    @CsvSource(
        {
            "2026-12-13, 10:00, InvalidDate",
            "2027-02-13, 10:00, InvalidDate",
            "2026-12-14, 09:30, InvalidSlot",
            "2026-12-14, 09:00, InvalidSlot",
            "2026-12-15, 10:00:01, InvalidSlot",
            "2026-12-15, 10:00:00.5, InvalidSlot",
        }
    )
    void cu04_2b_fechaFueraDelHorizonteTurnoIniciadoUHoraConSegundosNoCreaNadaNiLlamaANadie(LocalDate fecha, LocalTime hora, String esperado) {
        StartReservationResult resultado = casoDeUso.start(new StartReservationCommand(ANA, "ana.token", PROFESIONAL, fecha, hora));

        assertThat(resultado.getClass().getSimpleName()).isEqualTo(esperado);
        assertThat(repositorio.all()).isEmpty();
        assertThat(transacciones.opened()).isZero();
        verifyNoInteractions(catalogo, catedra, perfiles);
    }

    @Test
    void cu04_2_elUltimoDiaDelHorizonteSePuedeReservar() {
        // Hoy + 60 = viernes 12/02/2027.
        LocalDate dia60 = HOY.plusDays(60);
        when(catalogo.fetchAgenda(anyLong(), any())).thenReturn(Optional.of(agenda(true, horario(dia60.getDayOfWeek(), "09:00", "11:00", 30))));

        assertThat(casoDeUso.start(new StartReservationCommand(ANA, "ana.token", PROFESIONAL, dia60, DIEZ))).isInstanceOf(StartReservationResult.Started.class);
    }

    @Test
    void cu04_6a_unIdDeProfesionalQueNoPuedeExistirNoLlamaANadie() {
        assertThat(casoDeUso.start(new StartReservationCommand(ANA, "ana.token", 0, MARTES, DIEZ))).isEqualTo(new StartReservationResult.ProfessionalNotFound());

        verifyNoInteractions(catalogo, catedra);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 3a y 4a: pedidos repetidos, antes de llamar al catálogo
    // ---------------------------------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({ "CREATING_HOLD", "HOLD_CREATED", "PHONE_REQUESTED", "PHONE_REJECTED", "CONFIRMED" })
    void cu04_3a_elMismoTurnoEnCursoOConfirmadoDevuelveEseProcesoSinCrearNadaNiLlamarANadie(ReservationProcessStatus estado) {
        repositorio.put(existente(ANA, DIEZ, estado, 7));

        StartReservationResult resultado = casoDeUso.start(PEDIDO);

        assertThat(resultado).isInstanceOf(StartReservationResult.AlreadyExists.class);
        ReservationProcess devuelto = ((StartReservationResult.AlreadyExists) resultado).process();
        assertThat(devuelto.getId()).isEqualTo(id(7));
        assertThat(devuelto.getStatus()).isEqualTo(estado);
        assertThat(repositorio.all()).hasSize(1);
        assertThat(repositorio.writes()).isEmpty();
        verifyNoInteractions(catalogo, catedra);
    }

    @Test
    void cu04_3a_repetirElPedidoEsSeguroTambienConElCatalogoCaido() {
        when(catalogo.fetchAgenda(anyLong(), any())).thenThrow(new CatalogUnavailableException("caído", new RuntimeException()));
        repositorio.put(existente(ANA, DIEZ, WAITING_FOR_PHONE_REQUEST, 7));

        assertThat(casoDeUso.start(PEDIDO)).isInstanceOf(StartReservationResult.AlreadyExists.class);

        verifyNoInteractions(catalogo);
    }

    @ParameterizedTest
    @CsvSource({ "FAILED", "EXPIRED", "INVALID", "CANCELLED" })
    void cu04_3_unProcesoCerradoDelMismoTurnoNoImpideVolverAReservarlo(ReservationProcessStatus estado) {
        repositorio.put(existente(ANA, DIEZ, estado, 7));

        assertThat(iniciado(casoDeUso.start(PEDIDO)).getId()).isEqualTo(ID);
    }

    @Test
    void cu04_4a_conUnProcesoEnCursoParaOtroTurnoEs409ConSuIdSinLlamarAlCatalogo() {
        repositorio.put(existente(ANA, LocalTime.parse("09:00"), PHONE_REQUESTED, 7));

        assertThat(casoDeUso.start(PEDIDO)).isEqualTo(new StartReservationResult.AnotherInProgress(id(7)));

        assertThat(repositorio.writes()).isEmpty();
        verifyNoInteractions(catalogo, catedra);
    }

    @Test
    void cu04_4_elProcesoEnCursoDeOtroUsuarioNoCuenta() {
        repositorio.put(existente(77, DIEZ, PHONE_REQUESTED, 7));

        assertThat(casoDeUso.start(PEDIDO)).isInstanceOf(StartReservationResult.Started.class);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // El perfil del usuario
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void cu04_siElUsuarioDelTokenYaNoExisteNoSeCreaNadaNiSeLlamaANadie() {
        doReturn(Optional.empty()).when(perfiles).findById(ANA);

        assertThat(casoDeUso.start(PEDIDO)).isEqualTo(new StartReservationResult.UserNotFound());

        assertThat(repositorio.all()).isEmpty();
        verifyNoInteractions(catalogo, catedra);
    }

    @ParameterizedTest
    @CsvSource(value = { "NULL, Pérez", "A, Pérez", "Ana, ''" }, nullValues = "NULL")
    void cu04_siElNombreDelPerfilNoSirveComoPacienteNoSeCreaNadaNiSeLlamaANadie(String nombre, String apellido) {
        doReturn(Optional.of(new UserProfile(ANA, "ana.perez", nombre, apellido))).when(perfiles).findById(ANA);

        assertThat(casoDeUso.start(PEDIDO)).isEqualTo(new StartReservationResult.ProfileIncomplete());

        assertThat(repositorio.all()).isEmpty();
        verifyNoInteractions(catalogo, catedra);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 5a y 6a a 6c: catálogo
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void cu04_5a_conElCatalogoCaidoNoSeGuardaElProcesoNiSeLlamaALaCatedra() {
        when(catalogo.fetchAgenda(anyLong(), any())).thenThrow(new CatalogUnavailableException("503", Duration.ofSeconds(60)));

        assertThat(casoDeUso.start(PEDIDO)).isEqualTo(new StartReservationResult.CatalogUnavailable(Duration.ofSeconds(60)));

        assertThat(repositorio.all()).isEmpty();
        verifyNoInteractions(catedra);
    }

    @Test
    void cu04_5a_elTokenDeServicioRechazadoSeVeComoElCatalogoCaidoYUnaAgendaInvalidaComoTal() {
        when(catalogo.fetchAgenda(anyLong(), any())).thenThrow(new CatalogAccessDeniedException("403"));
        assertThat(casoDeUso.start(PEDIDO)).isEqualTo(new StartReservationResult.CatalogUnavailable(null));

        // doThrow: con when(...) se ejecutaría el stub anterior, que lanza.
        doThrow(new CatalogInvalidResponseException("JSON roto")).when(catalogo).fetchAgenda(anyLong(), any());
        assertThat(casoDeUso.start(PEDIDO)).isEqualTo(new StartReservationResult.UpstreamInvalidResponse());

        assertThat(repositorio.all()).isEmpty();
        verifyNoInteractions(catedra);
    }

    @Test
    void cu04_6a_siElProfesionalNoExisteEnElCatalogoNoSeCreaNada() {
        when(catalogo.fetchAgenda(anyLong(), any())).thenReturn(Optional.empty());

        assertThat(casoDeUso.start(PEDIDO)).isEqualTo(new StartReservationResult.ProfessionalNotFound());

        assertThat(repositorio.all()).isEmpty();
        verifyNoInteractions(catedra);
    }

    @Test
    void cu04_6b_siElProfesionalEstaDeshabilitadoNoSeCreaNada() {
        when(catalogo.fetchAgenda(anyLong(), any())).thenReturn(Optional.of(new ProfessionalAgenda(7, profesional(false), List.of())));

        assertThat(casoDeUso.start(PEDIDO)).isEqualTo(new StartReservationResult.ProfessionalDisabled());

        assertThat(repositorio.all()).isEmpty();
        verifyNoInteractions(catedra);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        {
            "no alineado a la duración, 2026-12-15, 10:15",
            "antes del horario, 2026-12-15, 08:30",
            "el que terminaría después del fin, 2026-12-15, 11:00",
            "otro día de la semana, 2026-12-16, 10:00",
        }
    )
    void cu04_6c_unTurnoQueNoEsDeUnHorarioHabilitadoNoSeCreaNiSePideElHold(String caso, LocalDate fecha, LocalTime hora) {
        assertThat(casoDeUso.start(new StartReservationCommand(ANA, "ana.token", PROFESIONAL, fecha, hora))).isEqualTo(new StartReservationResult.InvalidSlot());

        assertThat(repositorio.all()).isEmpty();
        verifyNoInteractions(catedra);
    }

    @Test
    void cu04_7_losDatosDelProfesionalMasLargosQueLaColumnaSeRecortan() {
        AgendaProfessional largo = new AgendaProfessional(PROFESIONAL, "N".repeat(150), "Gomez", true, new AgendaCategory(1, "C".repeat(101), true));
        when(catalogo.fetchAgenda(anyLong(), any())).thenReturn(Optional.of(new ProfessionalAgenda(7, largo, List.of(horario(DayOfWeek.TUESDAY, "09:00", "11:00", 30)))));

        ReservationProcess proceso = iniciado(casoDeUso.start(PEDIDO));

        assertThat(proceso.getProfessional().firstName()).isEqualTo("N".repeat(100));
        assertThat(proceso.getProfessional().categoryName()).isEqualTo("C".repeat(100));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 7a: la carrera del doble pedido
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void cu04_7a_siOtroPedidoDelMismoTurnoSeGuardoJustoAntesSeDevuelveEseSinPedirOtroHold() {
        repositorio.beforeNextInsert(() -> repositorio.put(existente(ANA, DIEZ, CREATING_HOLD, 7)));

        StartReservationResult resultado = casoDeUso.start(PEDIDO);

        assertThat(resultado).isInstanceOf(StartReservationResult.AlreadyExists.class);
        assertThat(((StartReservationResult.AlreadyExists) resultado).process().getId()).isEqualTo(id(7));
        assertThat(repositorio.all()).hasSize(1);
        verifyNoInteractions(catedra);
    }

    @Test
    void cu04_7a_siElQueSeGuardoJustoAntesEsDeOtroTurnoEs409() {
        repositorio.beforeNextInsert(() -> repositorio.put(existente(ANA, LocalTime.parse("09:00"), CREATING_HOLD, 7)));

        assertThat(casoDeUso.start(PEDIDO)).isEqualTo(new StartReservationResult.AnotherInProgress(id(7)));

        verifyNoInteractions(catedra);
    }

    @Test
    void cu04_7a_siElQueChocoYaTerminoElAltaSeIntentaUnaVezMas() {
        // El alta choca una vez, pero el otro proceso se cerró antes de poder leerlo: no hay a quién devolver.
        StartReservationResult resultado = casoDeUsoConCompetidorFantasma(1).start(PEDIDO);

        assertThat(iniciado(resultado).getStatus()).isEqualTo(WAITING_FOR_PHONE_REQUEST);
        verify(catedra, times(1)).createHold(any(), anyString());
    }

    @Test
    void cu04_7a_siElAltaVuelveAChocarYNoHayAQuienDevolverEs409SinId() {
        StartReservationResult resultado = casoDeUsoConCompetidorFantasma(2).start(PEDIDO);

        assertThat(resultado).isEqualTo(new StartReservationResult.AnotherInProgress(null));
        verifyNoInteractions(catedra);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Paso 8: crear el hold. Las cinco fallas sealed del puerto.
    // ---------------------------------------------------------------------------------------------------------------

    static Stream<Arguments> fallasDelHold() {
        return Stream.of(
            Arguments.of("no conectó", new CatedraUnavailableException("sin conexión"), "HOLD_NOT_SENT", new StartReservationResult.CatedraUnavailable()),
            Arguments.of("8a timeout o 5xx", new CatedraOutcomeUnknownException("timeout"), "HOLD_OUTCOME_UNKNOWN", new StartReservationResult.HoldOutcomeUnknown()),
            Arguments.of("8b SLOT_ALREADY_HELD", rechazo(409, CatedraErrorCode.SLOT_ALREADY_HELD), "SLOT_ALREADY_HELD", new StartReservationResult.SlotNotAvailable()),
            Arguments.of("8b SLOT_ALREADY_RESERVED", rechazo(409, CatedraErrorCode.SLOT_ALREADY_RESERVED), "SLOT_ALREADY_RESERVED", new StartReservationResult.SlotNotAvailable()),
            Arguments.of("8c INVALID_SLOT", rechazo(400, CatedraErrorCode.INVALID_SLOT), "INVALID_SLOT", new StartReservationResult.InvalidSlot()),
            Arguments.of("8c PROFESSIONAL_DISABLED", rechazo(400, CatedraErrorCode.PROFESSIONAL_DISABLED), "PROFESSIONAL_DISABLED", new StartReservationResult.ProfessionalDisabled()),
            Arguments.of("8d PROFESSIONAL_NOT_FOUND", rechazo(404, CatedraErrorCode.PROFESSIONAL_NOT_FOUND), "PROFESSIONAL_NOT_FOUND", new StartReservationResult.ProfessionalNotFound()),
            Arguments.of("8e VALIDATION_ERROR", rechazo(400, CatedraErrorCode.VALIDATION_ERROR), "VALIDATION_ERROR", new StartReservationResult.InternalError()),
            Arguments.of("8e code desconocido", rechazo(418, CatedraErrorCode.UNRECOGNIZED), "UNRECOGNIZED", new StartReservationResult.InternalError()),
            Arguments.of("8e 401 o 403", new CatedraAccessDeniedException("401"), "CATEDRA_ACCESS_DENIED", new StartReservationResult.CatedraUnavailable()),
            Arguments.of("fuera de contrato", new CatedraInvalidResponseException("3xx"), "CATEDRA_INVALID_RESPONSE", new StartReservationResult.UpstreamInvalidResponse())
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fallasDelHold")
    void cu04_8_siFallaElHoldElProcesoQuedaFailedConElMotivoYElPostNoSeRepite(String caso, CatedraException falla, String motivo, StartReservationResult esperado) {
        when(catedra.createHold(any(), anyString())).thenThrow(falla);

        assertThat(casoDeUso.start(PEDIDO)).isEqualTo(esperado);

        ReservationProcess proceso = repositorio.get(ID);
        assertThat(proceso.getStatus()).isEqualTo(FAILED);
        assertThat(proceso.getClosing()).contains(new Closing(motivo, null));
        assertThat(proceso.getHold()).isEmpty();
        // DPT-06: un solo POST del hold, pase lo que pase, y sin hold no hay confirm.
        verify(catedra, times(1)).createHold(any(), anyString());
        verify(catedra, never()).confirmHold(anyString(), anyString(), any());
    }

    @Test
    void cu04_10_siNoSePuedeGuardarElHoldNoSeConfirmaYElProcesoQuedaComoEstaba() {
        repositorio.failNextSaves(1, new DataAccessResourceFailureException("la base no responde"));

        assertThat(casoDeUso.start(PEDIDO)).isEqualTo(new StartReservationResult.HoldNotRecorded());

        assertThat(repositorio.get(ID).getStatus()).isEqualTo(CREATING_HOLD);
        verify(catedra, times(1)).createHold(any(), anyString());
        verify(catedra, never()).confirmHold(anyString(), anyString(), any());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Paso 11: confirmar el hold. Las cinco fallas sealed del puerto.
    // ---------------------------------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void cu04_11a_siElConfirmNoRespondeONoConectaElProcesoQuedaEnHoldCreatedYSeResponde202(boolean conecto) {
        when(catedra.confirmHold(anyString(), anyString(), any())).thenThrow(
            conecto ? new CatedraOutcomeUnknownException("timeout") : new CatedraUnavailableException("sin conexión")
        );

        ReservationProcess proceso = iniciado(casoDeUso.start(PEDIDO));

        assertThat(proceso.getStatus()).isEqualTo(HOLD_CREATED);
        assertThat(proceso.getHold()).contains(HOLD);
        assertThat(repositorio.get(ID).getStatus()).isEqualTo(HOLD_CREATED);
        verify(catedra, times(1)).confirmHold(anyString(), anyString(), any());
    }

    @Test
    void cu04_11b_siElHoldVencioElProcesoPasaAExpired() {
        when(catedra.confirmHold(anyString(), anyString(), any())).thenThrow(rechazo(404, CatedraErrorCode.HOLD_EXPIRED));

        assertThat(casoDeUso.start(PEDIDO)).isEqualTo(new StartReservationResult.Expired());

        assertThat(repositorio.get(ID).getStatus()).isEqualTo(EXPIRED);
        assertThat(repositorio.get(ID).getClosing()).contains(new Closing("HOLD_EXPIRED", null));
    }

    @Test
    void cu04_11d_siElProcesoYaEstabaConfirmadoEsUnExito() {
        when(catedra.confirmHold(anyString(), anyString(), any())).thenThrow(rechazo(409, CatedraErrorCode.RESERVATION_PROCESS_ALREADY_CONFIRMED));

        ReservationProcess proceso = iniciado(casoDeUso.start(PEDIDO));

        assertThat(proceso.getStatus()).isEqualTo(WAITING_FOR_PHONE_REQUEST);
        // La cátedra no informó un vencimiento nuevo: se conserva el del hold.
        assertThat(proceso.getHold()).contains(HOLD);
    }

    static Stream<Arguments> rechazosDelConfirm() {
        return Stream.of(
            Arguments.of("11c SLOT_ALREADY_RESERVED", rechazo(409, CatedraErrorCode.SLOT_ALREADY_RESERVED), "SLOT_ALREADY_RESERVED", new StartReservationResult.SlotNotAvailable()),
            Arguments.of("11c INVALID_SLOT", rechazo(400, CatedraErrorCode.INVALID_SLOT), "INVALID_SLOT", new StartReservationResult.InvalidSlot()),
            Arguments.of("11c PROFESSIONAL_NOT_FOUND", rechazo(404, CatedraErrorCode.PROFESSIONAL_NOT_FOUND), "PROFESSIONAL_NOT_FOUND", new StartReservationResult.ProfessionalNotFound()),
            Arguments.of("11e HOLD_NOT_FOUND", rechazo(404, CatedraErrorCode.HOLD_NOT_FOUND), "HOLD_NOT_FOUND", new StartReservationResult.InternalError()),
            Arguments.of("11e HOLD_OWNER_MISMATCH", rechazo(403, CatedraErrorCode.HOLD_OWNER_MISMATCH), "HOLD_OWNER_MISMATCH", new StartReservationResult.InternalError()),
            Arguments.of("11e EXTERNAL_PATIENT_MISMATCH", rechazo(400, CatedraErrorCode.EXTERNAL_PATIENT_MISMATCH), "EXTERNAL_PATIENT_MISMATCH", new StartReservationResult.InternalError()),
            Arguments.of("11e VALIDATION_ERROR", rechazo(400, CatedraErrorCode.VALIDATION_ERROR), "VALIDATION_ERROR", new StartReservationResult.InternalError()),
            Arguments.of("11e 401 o 403", new CatedraAccessDeniedException("403"), "CATEDRA_ACCESS_DENIED", new StartReservationResult.CatedraUnavailable()),
            Arguments.of("fuera de contrato", new CatedraInvalidResponseException("4xx sin code"), "CATEDRA_INVALID_RESPONSE", new StartReservationResult.UpstreamInvalidResponse())
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rechazosDelConfirm")
    void cu04_11_siLaCatedraRechazaElConfirmElProcesoQuedaFailedConElHoldGuardado(String caso, CatedraException falla, String motivo, StartReservationResult esperado) {
        when(catedra.confirmHold(anyString(), anyString(), any())).thenThrow(falla);

        assertThat(casoDeUso.start(PEDIDO)).isEqualTo(esperado);

        ReservationProcess proceso = repositorio.get(ID);
        assertThat(proceso.getStatus()).isEqualTo(FAILED);
        assertThat(proceso.getClosing()).contains(new Closing(motivo, null));
        assertThat(proceso.getHold()).contains(HOLD);
        verify(catedra, times(1)).confirmHold(anyString(), anyString(), any());
    }

    @Test
    void cu04_13_siNoSePuedeGuardarDespuesDel202SeRespondeIgualConElUltimoEstadoGuardado() {
        when(catedra.confirmHold(anyString(), anyString(), any())).thenAnswer(invocation -> {
            repositorio.failNextSaves(3, new DataAccessResourceFailureException("la base no responde"));
            return new HoldConfirmation("process-a92b47d0", VENCE_CONFIRM);
        });

        ReservationProcess proceso = iniciado(casoDeUso.start(PEDIDO));

        assertThat(proceso.getStatus()).isEqualTo(HOLD_CREATED);
        assertThat(repositorio.get(ID).getStatus()).isEqualTo(HOLD_CREATED);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 13a y 13b: REST y Kafka en distinto orden
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void cu04_13a_siElPedidoDeTelefonoLlegoAntesQueEl202ElProcesoNoRetrocede() {
        // El listener procesa AdditionalInformationRequested mientras el confirm todavía no respondió.
        when(catedra.confirmHold(anyString(), anyString(), any())).thenAnswer(invocation -> {
            repositorio.changeFromOutside(ID, StartReservationUseCaseImplTest::conPedidoDeTelefono);
            return new HoldConfirmation("process-a92b47d0", VENCE_CONFIRM);
        });

        ReservationProcess proceso = iniciado(casoDeUso.start(PEDIDO));

        assertThat(proceso.getStatus()).isEqualTo(PHONE_REQUESTED);
        assertThat(proceso.getPhoneRequest()).contains(new PhoneRequest("request-event-8a31", "Ingrese un teléfono"));
        assertThat(repositorio.get(ID).getStatus()).isEqualTo(PHONE_REQUESTED);
        // El 202 no se guardó: sería retroceder.
        assertThat(repositorio.writes()).containsExactly("insert CREATING_HOLD", "save HOLD_CREATED");
    }

    @Test
    void cu04_13a_siElEventoCambiaLaFilaEntreLaLecturaYElGuardadoSeReleeYNoSePisa() {
        // Más fino: el evento se confirma después de que este pedido leyó la fila y antes de su UPDATE (choque de versión).
        when(catedra.confirmHold(anyString(), anyString(), any())).thenAnswer(invocation -> {
            repositorio.beforeNextSave(() -> repositorio.changeFromOutside(ID, StartReservationUseCaseImplTest::conPedidoDeTelefono));
            return new HoldConfirmation("process-a92b47d0", VENCE_CONFIRM);
        });

        ReservationProcess proceso = iniciado(casoDeUso.start(PEDIDO));

        assertThat(proceso.getStatus()).isEqualTo(PHONE_REQUESTED);
        assertThat(repositorio.get(ID).getStatus()).isEqualTo(PHONE_REQUESTED);
        assertThat(repositorio.get(ID).getPhoneRequest()).isPresent();
    }

    @Test
    void cu04_13b_siElProcesoYaSeCerroPorUnEventoEl202NoLoModifica() {
        when(catedra.confirmHold(anyString(), anyString(), any())).thenAnswer(invocation -> {
            repositorio.changeFromOutside(ID, StartReservationUseCaseImplTest::cerrado);
            return new HoldConfirmation("process-a92b47d0", VENCE_CONFIRM);
        });

        ReservationProcess proceso = iniciado(casoDeUso.start(PEDIDO));

        assertThat(proceso.getStatus()).isEqualTo(EXPIRED);
        assertThat(repositorio.get(ID).getStatus()).isEqualTo(EXPIRED);
    }

    @Test
    void cu04_13_unRechazoDelConfirmNoCierraUnProcesoQueYaAvanzoPorKafka() {
        when(catedra.confirmHold(anyString(), anyString(), any())).thenAnswer(invocation -> {
            repositorio.changeFromOutside(ID, StartReservationUseCaseImplTest::conPedidoDeTelefono);
            throw rechazo(404, CatedraErrorCode.HOLD_NOT_FOUND);
        });

        ReservationProcess proceso = iniciado(casoDeUso.start(PEDIDO));

        assertThat(proceso.getStatus()).isEqualTo(PHONE_REQUESTED);
        assertThat(repositorio.get(ID).getStatus()).isEqualTo(PHONE_REQUESTED);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Ayudas
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * El alta choca {@code veces} veces con un proceso que nunca se llega a leer (se cerró en el medio): un repositorio
     * que lanza el conflicto sin tener ninguna fila intermedia.
     */
    private StartReservationUseCaseImpl casoDeUsoConCompetidorFantasma(int veces) {
        int[] quedan = { veces };
        InMemoryReservationProcessRepository conFantasma = new InMemoryReservationProcessRepository(transacciones) {
            @Override
            public ReservationProcess insert(ReservationProcess process) {
                if (quedan[0]-- > 0) {
                    transacciones.requireActive();
                    throw new IntermediateProcessConflictException(null);
                }
                return super.insert(process);
            }
        };
        doReturn(Optional.of(new UserProfile(ANA, "ana.perez", "Ana", "Pérez"))).when(perfiles).findById(ANA);
        return new StartReservationUseCaseImpl(
            conFantasma,
            perfiles,
            catalogo,
            catedra,
            new ReservationProcessUpdater(conFantasma, transacciones),
            () -> ID,
            new AvailabilityPolicy(MENDOZA, 60),
            Clock.fixed(AHORA, ZoneOffset.UTC),
            transacciones
        );
    }

    /** Registra el pedido si se hizo adentro de una transacción y devuelve la respuesta simulada. */
    private <T> T afuera(String pedido, T respuesta) {
        if (transacciones.isActive()) {
            pedidosAdentroDeUnaTransaccion.add(pedido);
        }
        return respuesta;
    }

    private static ReservationProcess iniciado(StartReservationResult resultado) {
        assertThat(resultado).isInstanceOf(StartReservationResult.Started.class);
        return ((StartReservationResult.Started) resultado).process();
    }

    private static ReservationProcessId id(int n) {
        return new ReservationProcessId(UUID.fromString("00000000-0000-4000-9000-" + "%012d".formatted(n)));
    }

    /** Un proceso ya guardado de ese usuario, para el martes a esa hora, llevado hasta ese estado por el modelo. */
    private static ReservationProcess existente(long userId, LocalTime hora, ReservationProcessStatus estado, int n) {
        ReservationProcess proceso = ReservationProcess.start(
            id(n),
            new ProcessOwner(userId, "login-" + userId),
            new AppointmentSlot(PROFESIONAL, MARTES, hora, hora.plusMinutes(30)),
            new ProfessionalSnapshot("Ana", "Gomez", "Clínica", 30),
            new PatientName("Ana", "Pérez"),
            AHORA.minusSeconds(60)
        );
        Instant antes = AHORA.minusSeconds(30);
        CatedraHold hold = new CatedraHold("hold-" + n, "process-" + n, VENCE);
        switch (estado) {
            case CREATING_HOLD -> {}
            case HOLD_CREATED -> proceso.holdCreated(hold, antes);
            case WAITING_FOR_PHONE_REQUEST -> {
                proceso.holdCreated(hold, antes);
                proceso.confirmAccepted(VENCE, antes);
            }
            case PHONE_REQUESTED -> {
                proceso.holdCreated(hold, antes);
                proceso.phoneRequested(new PhoneRequest("request-" + n, null), VENCE, antes);
            }
            case PHONE_REJECTED -> {
                proceso.holdCreated(hold, antes);
                proceso.phoneRequested(new PhoneRequest("request-" + n, null), VENCE, antes);
                proceso.phoneSubmitted(PhoneNumber.of("2615551234"), "enviado-" + n, antes);
                proceso.phoneRejected(new PhoneRejection("INVALID_PHONE_NUMBER", null), VENCE, antes);
            }
            case CONFIRMED -> {
                proceso.holdCreated(hold, antes);
                proceso.confirmed(new Confirmation(8251, antes), antes);
            }
            case CANCELLED -> {
                proceso.holdCreated(hold, antes);
                proceso.confirmed(new Confirmation(8251, antes), antes);
                proceso.cancelled(new Cancellation(null, antes), antes);
            }
            case EXPIRED -> {
                proceso.holdCreated(hold, antes);
                proceso.expired(new Closing("HOLD_EXPIRED", null), antes);
            }
            case INVALID -> {
                proceso.holdCreated(hold, antes);
                proceso.invalid(new Closing("REQUEST_EVENT_MISMATCH", null), antes);
            }
            case FAILED -> proceso.failed(new Closing("SLOT_ALREADY_HELD", null), antes);
            default -> throw new IllegalArgumentException("Estado no previsto en el test: " + estado);
        }
        assertThat(proceso.getStatus()).isEqualTo(estado);
        return proceso;
    }

    /** Lo que hace CU-05 cuando llega {@code AdditionalInformationRequested}. */
    private static ReservationProcess conPedidoDeTelefono(ReservationProcess proceso) {
        proceso.phoneRequested(new PhoneRequest("request-event-8a31", "Ingrese un teléfono"), VENCE, AHORA);
        return proceso;
    }

    /** Lo que hace CU-07 cuando llega {@code AppointmentProcessExpired}. */
    private static ReservationProcess cerrado(ReservationProcess proceso) {
        proceso.expired(new Closing("HOLD_EXPIRED", null), AHORA);
        return proceso;
    }

    private static CatedraRejectedException rechazo(int status, CatedraErrorCode code) {
        return new CatedraRejectedException(status, code, status + " " + code);
    }

    private static ProfessionalAgenda agenda(boolean habilitado, WeeklySchedule... horarios) {
        List<WeeklySchedule> lista = horarios.length == 0 ? List.of(horario(DayOfWeek.TUESDAY, "09:00", "11:00", 30)) : List.of(horarios);
        return new ProfessionalAgenda(7, profesional(habilitado), habilitado ? lista : List.of());
    }

    private static AgendaProfessional profesional(boolean habilitado) {
        return new AgendaProfessional(PROFESIONAL, "Ana", "Gomez", habilitado, new AgendaCategory(1, "Clínica", true));
    }

    private static WeeklySchedule horario(DayOfWeek dia, String inicio, String fin, int minutos) {
        return new WeeklySchedule(1, dia, LocalTime.parse(inicio), LocalTime.parse(fin), minutos);
    }
}
