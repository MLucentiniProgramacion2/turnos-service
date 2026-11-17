package ar.edu.um.programacion2.turnos.reservation.infrastructure.web;

import static ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.ReservationTestDatabase.usuario;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.um.programacion2.turnos.IntegrationTest;
import ar.edu.um.programacion2.turnos.repository.UserRepository;
import ar.edu.um.programacion2.turnos.reservation.ReservationTestTokens;
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
import java.time.LocalTime;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * CU-09 de punta a punta: JWT real, PostgreSQL real y la cátedra como servidor HTTP simulado, que responde con el formato
 * del contrato §11 (incluidos el teléfono y los nombres del paciente, que turnos tiene que descartar).
 */
@AutoConfigureMockMvc
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class MyReservationsControllerIT {

    private static final String URL = "/api/reservation-processes";
    private static final String TELEFONO = "5492615551234";
    private static final FakeHttpServer CATEDRA = iniciar();

    @DynamicPropertySource
    static void servidores(DynamicPropertyRegistry registry) {
        registry.add("catedra.api.url", () -> CATEDRA.baseUrl().toString());
    }

    @AfterAll
    static void detener() {
        CATEDRA.close();
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ReservationProcessRepository procesos;

    @Autowired
    private ReservationProcessMapper mapper;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private EntityManager em;

    @Autowired
    private PostgreSQLContainer database;

    @Value("${jhipster.security.authentication.jwt.base64-secret}")
    private String jwtKey;

    private long ana;
    private long beto;

    @BeforeEach
    void preparar() {
        ReservationTestDatabase.limpiar(tx, em);
        CATEDRA.reset();
        ana = tx.execute(status -> userRepository.saveAndFlush(usuario("reserva-ana")).getId());
        beto = tx.execute(status -> userRepository.saveAndFlush(usuario("reserva-beto")).getId());
    }

    @AfterEach
    void limpiar() {
        ReservationTestDatabase.limpiar(tx, em);
    }

    @Test
    void cu09_principal_laPaginaConvergidaConElFormatoDeLaPaginaDelCatalogo(CapturedOutput salida) throws Exception {
        guardar(ReservationProcessStatus.PHONE_SUBMITTED, ana, 1, "10:00");
        guardar(ReservationProcessStatus.EXPIRED, ana, 2, "09:00");
        guardar(ReservationProcessStatus.CONFIRMED, beto, 3, "11:00");
        catedraResponde(1, reserva("process-a92b47d0-1", "user-" + ana, "CONFIRMED"));

        String respuesta = listar("", ana)
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.totalPages").value(1))
            .andExpect(jsonPath("$.catedraUpToDate").value(true))
            .andExpect(jsonPath("$.processes", hasSize(2)))
            .andExpect(jsonPath("$.processes[0].status").value("CONFIRMED"))
            .andExpect(jsonPath("$.processes[0].appointment.startTime").value("10:00:00"))
            .andExpect(jsonPath("$.processes[0].confirmation.confirmedAt").value("2026-12-14T12:25:00Z"))
            .andExpect(jsonPath("$.processes[1].status").value("EXPIRED"))
            .andReturn()
            .getResponse()
            .getContentAsString();

        // Nada de la integración ni datos personales, ni de la respuesta de la cátedra ni del proceso.
        assertThat(respuesta).doesNotContain("process-a92b47d0", "hold-", "reservationId", "8251", TELEFONO, "Juan", "user-");
        FakeHttpServer.ReceivedRequest pedido = CATEDRA.onlyRequest();
        assertThat(pedido.path()).isEqualTo("/api/appointments");
        // Una sola llamada, solo por el proceso que puede cambiar (el EXPIRED no amplía el rango).
        assertThat(pedido.query()).isEqualTo("externalPatientId=user-" + ana + "&from=2026-12-14&to=2026-12-14&page=0&size=200");
        assertThat(estado(1)).isEqualTo("CONFIRMED");
        assertThat(salida.getAll()).doesNotContain(TELEFONO);
    }

    @Test
    void cu09_2a_sinTokenEs401ConElTokenDeServicio403() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, ReservationTestTokens.servicio(jwtKey))).andExpect(status().isForbidden());
    }

    @Test
    void cu09_dpt14_unAdministradorVeSoloLoSuyo() throws Exception {
        guardar(ReservationProcessStatus.EXPIRED, ana, 1, "10:00");
        guardar(ReservationProcessStatus.FAILED, beto, 2, "11:00");

        mvc
            .perform(get(URL).header(HttpHeaders.AUTHORIZATION, ReservationTestTokens.admin(jwtKey, "reserva-beto", beto)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.processes[0].status").value("FAILED"));
    }

    @Test
    void cu09_3a_sinProcesosEsUnaListaVaciaSinLlamarALaCatedra() throws Exception {
        listar("", ana)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0))
            .andExpect(jsonPath("$.totalPages").value(0))
            .andExpect(jsonPath("$.catedraUpToDate").value(true))
            .andExpect(jsonPath("$.processes", hasSize(0)));
        assertThat(CATEDRA.requests()).isEmpty();
    }

    @Test
    void cu09_4b_conLaCatedraCaidaEs200ConLosDatosLocalesNoActualizados() throws Exception {
        guardar(ReservationProcessStatus.PHONE_SUBMITTED, ana, 1, "10:00");
        CATEDRA.respondWithoutBody(503);

        listar("", ana)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.catedraUpToDate").value(false))
            .andExpect(jsonPath("$.processes[0].status").value("PHONE_SUBMITTED"));
    }

    @Test
    void cu09_4c_conLaCredencialRechazadaEs200NoActualizadoSinElMotivo() throws Exception {
        guardar(ReservationProcessStatus.CONFIRMED, ana, 1, "10:00");
        CATEDRA.respondProblem(403, "STUDENT_INTEGRATION_NOT_FOUND");

        listar("", ana)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.catedraUpToDate").value(false))
            .andExpect(content().string(not(containsString("STUDENT_INTEGRATION"))));
    }

    @Test
    void cu09_5_unaReservaDeLaCatedraSinProcesoLocalNoSeMuestra() throws Exception {
        guardar(ReservationProcessStatus.CONFIRMED, ana, 1, "10:00");
        // La cátedra devuelve la de Ana y otra con el mismo externalPatientId que no tiene proceso local.
        catedraResponde(2, reserva("process-a92b47d0-1", "user-" + ana, "CONFIRMED") + "," + reserva("process-de-otra-base", "user-" + ana, "CONFIRMED"));

        listar("", ana).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.processes", hasSize(1)));
    }

    @Test
    void elFiltroAceptaVariosEstadosConComaORepitiendoElParametro() throws Exception {
        guardar(ReservationProcessStatus.EXPIRED, ana, 1, "09:00");
        guardar(ReservationProcessStatus.FAILED, ana, 2, "10:00");
        guardar(ReservationProcessStatus.INVALID, ana, 3, "11:00");

        listar("?status=EXPIRED,FAILED", ana).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
        listar("?status=EXPIRED&status=INVALID", ana).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
        listar("?status=INVALID", ana).andExpect(jsonPath("$.processes[0].status").value("INVALID"));
    }

    @Test
    void laPaginaYElTamanoSeRespetan() throws Exception {
        for (int n = 1; n <= 3; n++) {
            guardar(ReservationProcessStatus.EXPIRED, ana, n, "%02d:00".formatted(8 + n));
        }

        listar("?page=1&size=2", ana)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.size").value(2))
            .andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.totalPages").value(2))
            .andExpect(jsonPath("$.processes", hasSize(1)))
            .andExpect(jsonPath("$.processes[0].appointment.startTime").value("09:00:00"));
    }

    @Test
    void unEstadoDesconocidoOUnaPaginaFueraDeRangoSon400SinRepetirElValor() throws Exception {
        for (String parametros : new String[] { "?status=NO_EXISTE<b>", "?size=51", "?size=0", "?page=-1", "?page=abc<b>" }) {
            String cuerpo = listar(parametros, ana)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andReturn()
                .getResponse()
                .getContentAsString();
            assertThat(cuerpo).as(parametros).doesNotContain("NO_EXISTE", "<b>", "abc", "\"51\"", "rejectedValue");
        }
        assertThat(CATEDRA.requests()).isEmpty();
    }

    @Test
    void cu09_6b_siUnEventoConfirmaElProcesoMientrasSeEsperaALaCatedraSeConvergeSobreElEstadoNuevo() throws Exception {
        guardar(ReservationProcessStatus.PHONE_SUBMITTED, ana, 1, "10:00");
        // La cátedra ya lo canceló; mientras responde, otra transacción (el listener con AppointmentConfirmed) lo confirma y
        // sube la versión. CU-09 relee, aplica la cancelación sobre el CONFIRMED y conserva la confirmación del evento.
        CATEDRA.beforeResponding(
            "/api/appointments",
            () ->
                ejecutarEnOtraConexion(
                    "update reservation_process set status = 'CONFIRMED', reservation_id = 8251, confirmed_at = '2026-12-14 12:20:00'," +
                    " status_changed_at = now(), version = version + 1 where id = '00000000-0000-4000-9000-000000000001'"
                )
        );
        catedraResponde(1, reserva("process-a92b47d0-1", "user-" + ana, "CANCELLED").replace("\"cancelledAt\":null", "\"cancelledAt\":\"2026-12-14T12:50:00Z\""));

        listar("", ana)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.catedraUpToDate").value(true))
            .andExpect(jsonPath("$.processes[0].status").value("CANCELLED"))
            .andExpect(jsonPath("$.processes[0].confirmation.confirmedAt").value("2026-12-14T12:20:00Z"))
            .andExpect(jsonPath("$.processes[0].cancellation.cancelledAt").value("2026-12-14T12:50:00Z"));
        assertThat(estado(1)).isEqualTo("CANCELLED");
    }

    // ---------------------------------------------------------------------------------------------------------------

    private void ejecutarEnOtraConexion(String sql) {
        try (
            Connection otra = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
            Statement statement = otra.createStatement()
        ) {
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Falló el SQL en la otra conexión", e);
        }
    }

    private ResultActions listar(String parametros, long userId) throws Exception {
        String login = userId == ana ? "reserva-ana" : "reserva-beto";
        return mvc.perform(get(URL + parametros).header(HttpHeaders.AUTHORIZATION, ReservationTestTokens.usuario(jwtKey, login, userId)));
    }

    private void guardar(ReservationProcessStatus estado, long userId, int n, String hora) {
        ReservationProcessEntity entidad = ReservationTestDatabase.fila(mapper, estado, userId, n);
        entidad.setStartTime(LocalTime.parse(hora));
        entidad.setEndTime(LocalTime.parse(hora).plusMinutes(30));
        tx.executeWithoutResult(status -> procesos.insert(mapper.toDomain(entidad)));
    }

    private String estado(int n) {
        return tx.execute(status ->
            (String) em
                .createNativeQuery("select status from reservation_process where id = cast(? as uuid)")
                .setParameter(1, "00000000-0000-4000-9000-" + "%012d".formatted(n))
                .getSingleResult()
        );
    }

    private static void catedraResponde(int total, String items) {
        CATEDRA.respond(200, "application/json", "[" + items + "]", Map.of("X-Total-Count", String.valueOf(total)));
    }

    /** Un ítem del contrato §11, con el teléfono y los nombres del paciente que turnos descarta. */
    private static String reserva(String reservationProcessId, String externalPatientId, String estado) {
        return """
            {"reservationId":8251,"reservationProcessId":"%s","externalPatientId":"%s","appointmentDate":"2026-12-14",
             "startTime":"10:00:00","endTime":"10:30:00","professionalId":202,"professionalFirstName":"Ana","professionalLastName":"Gomez",
             "patientFirstName":"Juan","patientLastName":"Perez","patientPhone":"+%s","status":"%s","createdAt":"2026-12-14T12:20:00Z",
             "confirmedAt":"2026-12-14T12:25:00Z","cancelledAt":null,"cancellationReason":null}""".formatted(
                reservationProcessId,
                externalPatientId,
                TELEFONO,
                estado
            );
    }

    private static FakeHttpServer iniciar() {
        try {
            return FakeHttpServer.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
