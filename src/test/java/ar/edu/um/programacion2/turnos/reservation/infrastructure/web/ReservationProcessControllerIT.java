package ar.edu.um.programacion2.turnos.reservation.infrastructure.web;

import static ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.ReservationTestDatabase.usuario;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.um.programacion2.turnos.IntegrationTest;
import ar.edu.um.programacion2.turnos.domain.User;
import ar.edu.um.programacion2.turnos.repository.UserRepository;
import ar.edu.um.programacion2.turnos.reservation.ReservationTestTokens;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.client.FakeHttpServer;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.ReservationTestDatabase;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * CU-04 de punta a punta: JWT de usuario real, PostgreSQL real, y el catálogo y la cátedra como servidores HTTP simulados.
 * El reloj está fijo en el lunes 14/12/2026 a las 09:30 de Mendoza; el turno es el martes 15 a las 10:00.
 */
@AutoConfigureMockMvc
@IntegrationTest
class ReservationProcessControllerIT {

    private static final String URL = "/api/reservation-processes";
    private static final String HOLDS = "/api/appointment-holds";
    private static final String CONFIRM = "/confirm";
    private static final FakeHttpServer CATALOGO = iniciar();
    private static final FakeHttpServer CATEDRA = iniciar();

    private static final String AGENDA = """
        {"catalogVersion":7,
         "professional":{"id":202,"firstName":"Ana","lastName":"Gomez","enabled":true,"category":{"id":1,"name":"Clinica","enabled":true}},
         "weeklySchedules":[{"id":1,"dayOfWeek":"TUESDAY","startTime":"09:00:00","endTime":"11:00:00","slotDurationMinutes":30}]}
        """;
    private static final String HOLD_201 = """
        {"holdId":"hold-6c0c2f41","reservationProcessId":"process-a92b47d0","status":"HELD","expiresAt":"2026-12-14T12:40:00Z"}
        """;
    private static final String CONFIRM_202 = """
        {"reservationProcessId":"process-a92b47d0","status":"WAITING_FOR_PHONE","expiresAt":"2026-12-14T12:40:05Z"}
        """;
    private static final String TURNO = "{\"professionalId\":202,\"date\":\"2026-12-15\",\"startTime\":\"10:00\"}";

    @DynamicPropertySource
    static void servidores(DynamicPropertyRegistry registry) {
        registry.add("catalogo.api.url", () -> CATALOGO.baseUrl().toString());
        registry.add("catedra.api.url", () -> CATEDRA.baseUrl().toString());
    }

    @AfterAll
    static void detener() {
        CATALOGO.close();
        CATEDRA.close();
    }

    @MockitoBean
    private Clock clock;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private EntityManager em;

    @Autowired
    private PostgreSQLContainer database;

    @Value("${jhipster.security.authentication.jwt.base64-secret}")
    private String jwtKey;

    private long ana;

    @BeforeEach
    void preparar() {
        when(clock.instant()).thenReturn(Instant.parse("2026-12-14T12:30:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        ReservationTestDatabase.limpiar(tx, em);
        ana = tx.execute(status -> userRepository.saveAndFlush(usuario("reserva-ana")).getId());
        CATALOGO.reset();
        CATEDRA.reset();
        CATALOGO.respondJson(200, AGENDA);
        CATEDRA.routeJson(HOLDS, 201, HOLD_201);
        CATEDRA.routeJson(CONFIRM, 202, CONFIRM_202);
    }

    @AfterEach
    void limpiar() {
        ReservationTestDatabase.limpiar(tx, em);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Flujo principal y pedidos repetidos
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void cu04_principal_202ConElProcesoEsperandoElPedidoDeTelefonoYSinDatosDeLaIntegracion() throws Exception {
        String respuesta = reservar(TURNO)
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andExpect(jsonPath("$.status").value("WAITING_FOR_PHONE_REQUEST"))
            .andExpect(jsonPath("$.expiresAt").value("2026-12-14T12:40:05Z"))
            .andExpect(
                content()
                    .json(
                        """
                        {"appointment":{"professionalId":202,"date":"2026-12-15","startTime":"10:00:00","endTime":"10:30:00"},
                         "professional":{"firstName":"Ana","lastName":"Gomez","categoryName":"Clinica","slotDurationMinutes":30}}
                        """
                    )
            )
            .andExpect(jsonPath("$.phoneRequest").doesNotExist())
            .andExpect(jsonPath("$.closing").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();

        // Nada de la integración con la cátedra ni datos personales.
        assertThat(respuesta).doesNotContain("hold-6c0c2f41", "process-a92b47d0", "holdId", "reservationProcessId", "Pérez", "user-", "reserva-ana");
        assertThat(fila()).isEqualTo("WAITING_FOR_PHONE_REQUEST|hold-6c0c2f41|process-a92b47d0|reserva-ana|Ana|Pérez|10:00:00|10:30:00");

        FakeHttpServer.ReceivedRequest hold = CATEDRA.requestsTo(HOLDS).getFirst();
        assertThat(hold.method()).isEqualTo("POST");
        assertThat(hold.body()).isEqualTo("{\"professionalId\":202,\"date\":\"2026-12-15\",\"startTime\":\"10:00\",\"externalPatientId\":\"user-" + ana + "\"}");
        FakeHttpServer.ReceivedRequest confirm = CATEDRA.requestsTo(CONFIRM).getFirst();
        assertThat(confirm.path()).isEqualTo("/api/appointment-holds/hold-6c0c2f41/confirm");
        assertThat(confirm.body()).isEqualTo(
            "{\"externalPatientId\":\"user-" + ana + "\",\"patientFirstName\":\"Ana\",\"patientLastName\":\"Pérez\"}"
        );
        assertThat(CATALOGO.requests()).singleElement().satisfies(pedido -> assertThat(pedido.userLogin()).isEqualTo("reserva-ana"));
    }

    @Test
    void cu04_3a_elMismoPedidoOtraVezEs200ConElMismoProcesoYSinOtroHold() throws Exception {
        String id = idDe(reservar(TURNO).andExpect(status().isAccepted()));

        reservar(TURNO).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.status").value("WAITING_FOR_PHONE_REQUEST"));
        // La hora con segundos es el mismo turno.
        reservar("{\"professionalId\":202,\"date\":\"2026-12-15\",\"startTime\":\"10:00:00\"}").andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));

        assertThat(contar()).isEqualTo(1);
        assertThat(CATEDRA.requestsTo(HOLDS)).hasSize(1);
    }

    @Test
    void cu04_3a_repetirElPedidoEsSeguroTambienConElCatalogoCaido() throws Exception {
        String id = idDe(reservar(TURNO).andExpect(status().isAccepted()));
        CATALOGO.respondWithoutBody(503);

        reservar(TURNO).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));

        assertThat(CATALOGO.requests()).hasSize(1);
    }

    @Test
    void cu04_4a_otroTurnoConUnaReservaEnCursoEs409ConElIdDelProcesoEnCurso() throws Exception {
        String id = idDe(reservar(TURNO).andExpect(status().isAccepted()));

        reservar("{\"professionalId\":202,\"date\":\"2026-12-15\",\"startTime\":\"09:30\"}")
            .andExpect(status().isConflict())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("RESERVATION_IN_PROGRESS"))
            .andExpect(jsonPath("$.processId").value(id));

        assertThat(contar()).isEqualTo(1);
        assertThat(CATEDRA.requestsTo(HOLDS)).hasSize(1);
    }

    @Test
    void cu04_4_laReservaEnCursoDeOtroUsuarioNoBloqueaNiSeMuestra() throws Exception {
        reservar(TURNO).andExpect(status().isAccepted());
        long beto = tx.execute(status -> userRepository.saveAndFlush(usuario("reserva-beto")).getId());
        CATEDRA.routeJson(HOLDS, 201, HOLD_201.replace("hold-6c0c2f41", "hold-otro").replace("process-a92b47d0", "process-otro"));
        CATEDRA.routeJson(CONFIRM, 202, CONFIRM_202.replace("process-a92b47d0", "process-otro"));

        mvc
            .perform(
                post(URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"professionalId\":202,\"date\":\"2026-12-15\",\"startTime\":\"09:30\"}")
                    .header(HttpHeaders.AUTHORIZATION, ReservationTestTokens.usuario(jwtKey, "reserva-beto", beto))
            )
            .andExpect(status().isAccepted());

        assertThat(contar()).isEqualTo(2);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 2a y 2b: autorización y validación
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void cu04_2a_sinTokenEs401YConElTokenDeServicioEs403() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(TURNO)).andExpect(status().isUnauthorized());
        mvc
            .perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(TURNO).header(HttpHeaders.AUTHORIZATION, ReservationTestTokens.servicio(jwtKey)))
            .andExpect(status().isForbidden());

        assertThat(contar()).isZero();
        assertThat(CATALOGO.requests()).isEmpty();
    }

    @Test
    void cu04_laIdentidadSaleDelTokenUnUserIdEnElCuerpoSeIgnora() throws Exception {
        long beto = tx.execute(status -> userRepository.saveAndFlush(usuario("reserva-beto")).getId());

        reservar("{\"professionalId\":202,\"date\":\"2026-12-15\",\"startTime\":\"10:00\",\"userId\":" + beto + ",\"externalPatientId\":\"user-" + beto + "\"}")
            .andExpect(status().isAccepted());

        assertThat(duenos()).containsExactly(ana);
        assertThat(CATEDRA.requestsTo(HOLDS).getFirst().body()).contains("\"externalPatientId\":\"user-" + ana + "\"");
    }

    @Test
    void cu04_2b_sinUnCampoEsUn400DeValidacionConElCampo() throws Exception {
        reservar("{\"professionalId\":202,\"date\":\"2026-12-15\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.fieldErrors", hasSize(1)))
            .andExpect(jsonPath("$.fieldErrors[0].field").value("startTime"));
        reservar("{\"professionalId\":-3,\"date\":\"2026-12-15\",\"startTime\":\"10:00\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.fieldErrors[0].field").value("professionalId"));

        assertThat(contar()).isZero();
        assertThat(CATALOGO.requests()).isEmpty();
    }

    @Test
    void cu04_2b_unaFechaOUnaHoraConOtroFormatoEsUn400QueNoRepiteElValor() throws Exception {
        for (String cuerpo : List.of(
            "{\"professionalId\":202,\"date\":\"15/12/2026\",\"startTime\":\"10:00\"}",
            "{\"professionalId\":202,\"date\":\"2026-12-15\",\"startTime\":\"diezEnPunto\"}",
            "{\"professionalId\":\"doscientosDos\",\"date\":\"2026-12-15\",\"startTime\":\"10:00\"}",
            "{\"professionalId\":202,\"date\":\"2026-12-15\",\"startTime\":\"25:00\"}",
            "esto no es JSON"
        )) {
            reservar(cuerpo)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(content().string(not(containsString("15/12"))))
                .andExpect(content().string(not(containsString("diezEnPunto"))))
                .andExpect(content().string(not(containsString("doscientosDos"))))
                .andExpect(content().string(not(containsString("25:00"))))
                .andExpect(content().string(not(containsString("esto no"))))
                .andExpect(content().string(not(containsString("java."))));
        }
        assertThat(contar()).isZero();
        assertThat(CATALOGO.requests()).isEmpty();
    }

    @Test
    void cu04_2b_fechaFueraDelHorizonteOTurnoYaIniciadoEs400SinCrearNada() throws Exception {
        reservar("{\"professionalId\":202,\"date\":\"2027-02-13\",\"startTime\":\"10:00\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
        reservar("{\"professionalId\":202,\"date\":\"2026-12-13\",\"startTime\":\"10:00\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
        // Hoy a las 09:30, que es justo ahora, y con segundos.
        reservar("{\"professionalId\":202,\"date\":\"2026-12-14\",\"startTime\":\"09:30\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_SLOT"));
        reservar("{\"professionalId\":202,\"date\":\"2026-12-15\",\"startTime\":\"10:00:30\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_SLOT"));

        assertThat(contar()).isZero();
        assertThat(CATALOGO.requests()).isEmpty();
        assertThat(CATEDRA.requests()).isEmpty();
    }

    @Test
    void cu04_siElUsuarioDelTokenYaNoExisteEs401ConCode() throws Exception {
        mvc
            .perform(
                post(URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(TURNO)
                    .header(HttpHeaders.AUTHORIZATION, ReservationTestTokens.usuario(jwtKey, "reserva-borrado", 999_999))
            )
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        assertThat(CATALOGO.requests()).isEmpty();
    }

    @Test
    void cu04_conElPerfilSinNombreValidoEs409YNoSeLlamaANadie() throws Exception {
        tx.executeWithoutResult(status -> {
            User user = userRepository.findById(ana).orElseThrow();
            user.setFirstName("A");
        });

        reservar(TURNO).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROFILE_INCOMPLETE"));

        assertThat(contar()).isZero();
        assertThat(CATALOGO.requests()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Catálogo
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void cu04_5a_conElCatalogoCaidoEs503SinGuardarElProcesoNiLlamarALaCatedra() throws Exception {
        CATALOGO.respond(503, "application/problem+json", "{\"status\":503,\"code\":\"CATALOG_NOT_INITIALIZED\"}", Map.of("Retry-After", "45"));

        reservar(TURNO)
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string(HttpHeaders.RETRY_AFTER, "45"))
            .andExpect(jsonPath("$.code").value("CATALOG_UNAVAILABLE"));

        assertThat(contar()).isZero();
        assertThat(CATEDRA.requests()).isEmpty();
    }

    @Test
    void cu04_6_profesionalInexistenteDeshabilitadoOTurnoFueraDeLaAgendaNoCreanNada() throws Exception {
        CATALOGO.respondProblem(404, "PROFESSIONAL_NOT_FOUND");
        reservar(TURNO).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PROFESSIONAL_NOT_FOUND"));

        CATALOGO.respondJson(200, AGENDA.replace("\"enabled\":true,\"category\"", "\"enabled\":false,\"category\"").replaceAll("\"weeklySchedules\":\\[.*]", "\"weeklySchedules\":[]"));
        reservar(TURNO).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PROFESSIONAL_DISABLED"));

        CATALOGO.respondJson(200, AGENDA);
        reservar("{\"professionalId\":202,\"date\":\"2026-12-15\",\"startTime\":\"10:15\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_SLOT"));

        assertThat(contar()).isZero();
        assertThat(CATEDRA.requests()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Crear y confirmar el hold
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void cu04_8a_un5xxAlCrearElHoldEs503ConElAvisoElProcesoQuedaFailedYElPostNoSeRepite() throws Exception {
        CATEDRA.routeWithoutBody(HOLDS, 500);

        reservar(TURNO)
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("HOLD_OUTCOME_UNKNOWN"))
            .andExpect(jsonPath("$.detail").value(containsString("puede quedar bloqueado")));

        assertThat(estadoYMotivo()).isEqualTo("FAILED|HOLD_OUTCOME_UNKNOWN");
        assertThat(CATEDRA.requestsTo(HOLDS)).hasSize(1);
        assertThat(CATEDRA.requestsTo(CONFIRM)).isEmpty();
        // Un proceso FAILED no bloquea: el usuario puede volver a intentar.
        CATEDRA.routeJson(HOLDS, 201, HOLD_201);
        reservar(TURNO).andExpect(status().isAccepted());
    }

    @Test
    void cu04_8b_siOtroTomoElTurnoEs409YElDetailDeLaCatedraNoSeReenvia() throws Exception {
        CATEDRA.routeProblem(HOLDS, 409, "SLOT_ALREADY_HELD");

        reservar(TURNO)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SLOT_NOT_AVAILABLE"))
            .andExpect(content().string(not(containsString("detalle libre"))));

        assertThat(estadoYMotivo()).isEqualTo("FAILED|SLOT_ALREADY_HELD");
        assertThat(CATEDRA.requestsTo(CONFIRM)).isEmpty();
    }

    @Test
    void cu04_8e_siLaCatedraRechazaLaCredencialTecnicaEs503GenericoYQuedaFailed() throws Exception {
        CATEDRA.routeWithoutBody(HOLDS, 401);

        reservar(TURNO).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("CATEDRA_UNAVAILABLE"));

        assertThat(estadoYMotivo()).isEqualTo("FAILED|CATEDRA_ACCESS_DENIED");
    }

    @Test
    void cu04_11a_un5xxAlConfirmarEs202ConElProcesoEnHoldCreated() throws Exception {
        CATEDRA.routeWithoutBody(CONFIRM, 503);

        reservar(TURNO)
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("HOLD_CREATED"))
            .andExpect(jsonPath("$.expiresAt").value("2026-12-14T12:40:00Z"));

        assertThat(estadoYMotivo()).isEqualTo("HOLD_CREATED|");
        assertThat(CATEDRA.requestsTo(CONFIRM)).hasSize(1);
    }

    @Test
    void cu04_11b_siElHoldVencioEs409YElProcesoQuedaExpired() throws Exception {
        CATEDRA.routeProblem(CONFIRM, 404, "HOLD_EXPIRED");

        reservar(TURNO).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESERVATION_EXPIRED"));

        assertThat(estadoYMotivo()).isEqualTo("EXPIRED|HOLD_EXPIRED");
    }

    @Test
    void cu04_11d_siElProcesoYaEstabaConfirmadoEs202ComoUnExito() throws Exception {
        CATEDRA.routeProblem(CONFIRM, 409, "RESERVATION_PROCESS_ALREADY_CONFIRMED");

        reservar(TURNO).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("WAITING_FOR_PHONE_REQUEST"));

        assertThat(estadoYMotivo()).isEqualTo("WAITING_FOR_PHONE_REQUEST|");
    }

    /**
     * 13a con dos escritores reales: mientras la cátedra todavía no respondió el confirm, "el listener" (otra conexión)
     * pasa el proceso a {@code PHONE_REQUESTED}. El {@code 202} que llega después no lo hace retroceder.
     */
    @Test
    void cu04_13a_siElPedidoDeTelefonoLlegoAntesQueEl202SeRespondeElEstadoActualYNoSeRetrocede() throws Exception {
        CATEDRA.beforeResponding(CONFIRM, () ->
            ejecutarEnOtraConexion(
                "update reservation_process set status = 'PHONE_REQUESTED', request_event_id = 'request-event-8a31'," +
                    " phone_request_message = 'Ingrese un telefono', version = version + 1"
            )
        );

        reservar(TURNO)
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("PHONE_REQUESTED"))
            .andExpect(jsonPath("$.phoneRequest.message").value("Ingrese un telefono"));

        assertThat(estadoYMotivo()).isEqualTo("PHONE_REQUESTED|");
        Object version = tx.execute(status -> em.createNativeQuery("select version from reservation_process").getSingleResult());
        // Alta (0), HOLD_CREATED (1) y el evento (2): el 202 no escribió nada.
        assertThat(((Number) version).longValue()).isEqualTo(2);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Ayudas
    // ---------------------------------------------------------------------------------------------------------------

    private ResultActions reservar(String cuerpo) throws Exception {
        return mvc.perform(
            post(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo)
                .header(HttpHeaders.AUTHORIZATION, ReservationTestTokens.usuario(jwtKey, "reserva-ana", ana))
        );
    }

    private static String idDe(ResultActions respuesta) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(respuesta.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private long contar() {
        return tx.execute(status -> (Long) em.createQuery("select count(p) from ReservationProcessEntity p").getSingleResult());
    }

    @SuppressWarnings("unchecked")
    private List<Long> duenos() {
        return tx.execute(status ->
            ((List<Number>) em.createNativeQuery("select user_id from reservation_process").getResultList()).stream().map(Number::longValue).toList()
        );
    }

    private String fila() {
        return tx.execute(status ->
            (String) em
                .createNativeQuery(
                    "select status || '|' || hold_id || '|' || reservation_process_id || '|' || user_login || '|' || patient_first_name" +
                        " || '|' || patient_last_name || '|' || start_time::text || '|' || end_time::text from reservation_process"
                )
                .getSingleResult()
        );
    }

    private String estadoYMotivo() {
        return tx.execute(status ->
            (String) em.createNativeQuery("select status || '|' || coalesce(closing_reason, '') from reservation_process").getSingleResult()
        );
    }

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

    private static FakeHttpServer iniciar() {
        try {
            return FakeHttpServer.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
