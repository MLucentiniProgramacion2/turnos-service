package ar.edu.um.programacion2.turnos.reservation.application;

import static ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.ReservationTestDatabase.usuario;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import ar.edu.um.programacion2.turnos.IntegrationTest;
import ar.edu.um.programacion2.turnos.repository.UserRepository;
import ar.edu.um.programacion2.turnos.reservation.application.service.ReservationService;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReconciliationReport;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReconciliationReport.Ending;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ReservationProcessRepository;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.client.FakeHttpServer;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.ReservationTestDatabase;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.entity.ReservationProcessEntity;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.mapper.ReservationProcessMapper;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * CU-11 contra PostgreSQL real y la cátedra simulada por HTTP: la consulta de los intermedios y la carrera con un evento
 * de Kafka en los dos órdenes, con dos escritores reales (la otra transacción va por otra conexión, fuera del pool, como la
 * del listener). Los procesos son los de la matriz: el hold vence el 10/12/2026 a las 12:10 UTC.
 */
@IntegrationTest
class ReconciliationIT {

    /** Pasado {@code expiresAt} + 1 minuto. */
    private static final Instant VENCIDO = Instant.parse("2026-12-10T12:11:30Z");
    private static final FakeHttpServer CATEDRA = iniciar();

    @DynamicPropertySource
    static void servidores(DynamicPropertyRegistry registry) {
        registry.add("catedra.api.url", () -> CATEDRA.baseUrl().toString());
    }

    @AfterAll
    static void detener() {
        CATEDRA.close();
    }

    @MockitoBean
    private Clock clock;

    @MockitoSpyBean
    private ReservationProcessRepository procesos;

    @Autowired
    private ReservationService servicio;

    @Autowired
    private ReservationProcessMapper mapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private EntityManager em;

    @Autowired
    private PostgreSQLContainer database;

    private long ana;
    private long beto;

    @BeforeEach
    void preparar() {
        when(clock.instant()).thenReturn(VENCIDO);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        ReservationTestDatabase.limpiar(tx, em);
        CATEDRA.reset();
        ana = tx.execute(status -> userRepository.saveAndFlush(usuario("reserva-ana")).getId());
        beto = tx.execute(status -> userRepository.saveAndFlush(usuario("reserva-beto")).getId());
    }

    @AfterEach
    void limpiar() {
        reset(procesos);
        ReservationTestDatabase.limpiar(tx, em);
    }

    @Test
    void losIntermediosVienenDelCambioMasViejoAlMasNuevoYSinLosDemas() {
        UUID nuevo = guardar(ReservationProcessStatus.PHONE_REQUESTED, ana, 1, "2026-12-10 11:40:00");
        UUID viejo = guardar(ReservationProcessStatus.HOLD_CREATED, beto, 2, "2026-12-10 11:10:00");
        guardar(ReservationProcessStatus.CONFIRMED, ana, 3, "2026-12-10 10:00:00");
        guardar(ReservationProcessStatus.EXPIRED, beto, 4, "2026-12-10 10:00:00");

        List<ReservationProcess> intermedios = tx.execute(status -> procesos.findIntermediates());

        assertThat(intermedios).extracting(proceso -> proceso.getId().value()).containsExactly(viejo, nuevo);
    }

    @Test
    void cu11_3b_cierraUnProcesoVencidoQueLaCatedraInformaFailedYLoGuarda() {
        UUID id = guardar(ReservationProcessStatus.PHONE_REQUESTED, ana, 1, "2026-12-10 11:30:00");
        CATEDRA.respond(200, "application/json", "[" + reserva("FAILED", ana) + "]", Map.of("X-Total-Count", "1"));

        ReconciliationReport reporte = servicio.reconcilePendingProcesses();

        assertThat(reporte).isEqualTo(new ReconciliationReport.Completed(1, 1, 0, 0, Ending.FINISHED));
        assertThat(columna(id, "status || '|' || closing_reason")).isEqualTo("EXPIRED|FAILED_IN_CATEDRA");
        assertThat(CATEDRA.onlyRequest().query()).isEqualTo("externalPatientId=user-" + ana + "&from=2026-12-14&to=2026-12-14&page=0&size=200");
    }

    /**
     * 5a, orden A: {@code AppointmentConfirmed} se guarda <strong>mientras la cátedra responde la lista</strong> (que todavía
     * no lo tenía). La decisión sobre el proceso leído era "ausente → EXPIRED"; recalculada sobre el releído, no hay nada que
     * hacer.
     */
    @Test
    void cu11_5a_unEventoQueSeGuardaMientrasSeEsperaLaListaGana() {
        UUID id = guardar(ReservationProcessStatus.PHONE_SUBMITTED, ana, 1, "2026-12-10 11:30:00");
        CATEDRA.beforeResponding("/api/appointments", () -> confirmarEnOtraConexion(id));
        CATEDRA.respond(200, "application/json", "[]", Map.of("X-Total-Count", "0"));

        ReconciliationReport reporte = servicio.reconcilePendingProcesses();

        assertThat(columna(id, "status")).isEqualTo("CONFIRMED");
        assertThat(reporte).isEqualTo(new ReconciliationReport.Completed(1, 0, 1, 0, Ending.FINISHED));
    }

    /**
     * 5a, orden B: el evento se guarda <strong>entre la relectura y el guardado de CU-11</strong>. CU-11 pierde por
     * {@code @Version}, relee, recalcula y no hace nada.
     */
    @Test
    void cu11_5a_unEventoQueSeGuardaEntreLaRelecturaYElGuardadoGanaPorVersion() {
        UUID id = guardar(ReservationProcessStatus.PHONE_SUBMITTED, ana, 1, "2026-12-10 11:30:00");
        CATEDRA.respond(200, "application/json", "[]", Map.of("X-Total-Count", "0"));
        boolean[] yaChoco = { false };
        doAnswer(invocacion -> {
            if (!yaChoco[0]) {
                yaChoco[0] = true;
                confirmarEnOtraConexion(id);
            }
            return invocacion.callRealMethod();
        })
            .when(AopTestUtils.<ReservationProcessRepository>getUltimateTargetObject(procesos))
            .save(any());

        ReconciliationReport reporte = servicio.reconcilePendingProcesses();

        assertThat(yaChoco[0]).isTrue();
        assertThat(columna(id, "status")).isEqualTo("CONFIRMED");
        assertThat(reporte).isEqualTo(new ReconciliationReport.Completed(1, 0, 1, 0, Ending.FINISHED));
    }

    @Test
    void cu11_4b_conLaCredencialRechazadaNoCambiaNadaYCortaLaEjecucion() {
        UUID uno = guardar(ReservationProcessStatus.PHONE_REQUESTED, ana, 1, "2026-12-10 11:30:00");
        UUID dos = guardar(ReservationProcessStatus.PHONE_SUBMITTED, beto, 2, "2026-12-10 11:35:00");
        CATEDRA.respondProblem(403, "STUDENT_INTEGRATION_NOT_FOUND");

        ReconciliationReport reporte = servicio.reconcilePendingProcesses();

        assertThat(reporte).isEqualTo(new ReconciliationReport.Completed(1, 0, 0, 0, Ending.ACCESS_DENIED));
        assertThat(CATEDRA.requests()).hasSize(1);
        assertThat(columna(uno, "status")).isEqualTo("PHONE_REQUESTED");
        assertThat(columna(dos, "status")).isEqualTo("PHONE_SUBMITTED");
    }

    // ---------------------------------------------------------------------------------------------------------------

    private UUID guardar(ReservationProcessStatus estado, long userId, int n, String ultimoCambio) {
        ReservationProcessEntity entidad = ReservationTestDatabase.fila(mapper, estado, userId, n);
        entidad.setStatusChangedAt(Instant.parse(ultimoCambio.replace(' ', 'T') + "Z"));
        return tx.execute(status -> procesos.insert(mapper.toDomain(entidad))).getId().value();
    }

    /** Lo que guarda el listener con {@code AppointmentConfirmed}, en otra conexión y con la versión nueva. */
    private void confirmarEnOtraConexion(UUID id) {
        try (
            Connection otra = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
            Statement statement = otra.createStatement()
        ) {
            statement.executeUpdate(
                "update reservation_process set status = 'CONFIRMED', reservation_id = 8251, confirmed_at = '2026-12-10 12:09:00'," +
                " version = version + 1 where id = '" + id + "'"
            );
        } catch (SQLException e) {
            throw new IllegalStateException("Falló el SQL en la otra conexión", e);
        }
    }

    private String columna(UUID id, String expresion) {
        return tx.execute(status ->
            String.valueOf(
                em.createNativeQuery("select " + expresion + " from reservation_process where id = cast(? as uuid)").setParameter(1, id.toString()).getSingleResult()
            )
        );
    }

    private static String reserva(String estado, long userId) {
        return """
            {"reservationId":8251,"reservationProcessId":"process-a92b47d0-1","externalPatientId":"user-%d","appointmentDate":"2026-12-14",
             "startTime":"09:00:00","status":"%s","confirmedAt":null,"cancelledAt":null,"cancellationReason":null,
             "patientPhone":"+5492615551234"}""".formatted(userId, estado);
    }

    private static FakeHttpServer iniciar() {
        try {
            return FakeHttpServer.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
