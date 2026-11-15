package ar.edu.um.programacion2.turnos.reservation;

import static ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.ReservationTestDatabase.usuario;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.um.programacion2.turnos.IntegrationTest;
import ar.edu.um.programacion2.turnos.repository.UserRepository;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.PhoneSubmissionPublisher;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ReservationProcessRepository;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.client.FakeHttpServer;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.ReservationTestDatabase;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Los flujos de la etapa de punta a punta, con todas las piezas reales salvo la cátedra y el catálogo: JWT, PostgreSQL,
 * los controllers, el productor publicando en un broker de Kafka y el listener consumiendo de él. La cátedra hace de las
 * dos cosas que hace la real: responde el hold y el confirm por HTTP ({@link FakeHttpServer}) y publica los eventos en el
 * topic de acciones (el test, con {@link KafkaTestBroker}).
 * <p>
 * Los dos flujos que pide el enunciado (§11): una reserva que termina {@code CONFIRMED} y una que termina {@code EXPIRED}
 * sin informar el teléfono. Además, un rechazo con reenvío, y la carrera entre CU-06 y CU-07 en los dos órdenes.
 * <p>
 * El reloj está fijo en el lunes 14/12/2026 a las 09:30 de Mendoza (12:30 UTC); el turno es el martes 15 a las 10:00.
 */
@AutoConfigureMockMvc
@IntegrationTest
@TestPropertySource(properties = "catedra.kafka.listener-enabled=true")
class ReservationFlowsIT {

    private static final String ACCIONES = KafkaTestBroker.createTopic("alumnos.turnos.acciones.flujos-");
    private static final String TELEFONO = KafkaTestBroker.createTopic("catedra.turnos.telefono.flujos-");
    private static final String GRUPO = "alumnos-flujos-turnos";
    private static final FakeHttpServer CATALOGO = iniciar();
    private static final FakeHttpServer CATEDRA = iniciar();
    private static final Duration MAX_ESPERA = Duration.ofSeconds(30);

    private static final String AGENDA = """
        {"catalogVersion":7,
         "professional":{"id":202,"firstName":"Ana","lastName":"Gomez","enabled":true,"category":{"id":1,"name":"Clinica","enabled":true}},
         "weeklySchedules":[{"id":1,"dayOfWeek":"TUESDAY","startTime":"09:00:00","endTime":"11:00:00","slotDurationMinutes":30}]}
        """;
    private static final String TURNO = "{\"professionalId\":202,\"date\":\"2026-12-15\",\"startTime\":\"10:00\"}";

    /** Mensajes publicados en el topic de acciones desde que arrancó la clase. */
    private static long accionesPublicadas;

    /** Teléfonos publicados por turnos en el topic de teléfono desde que arrancó la clase (un 202 de CU-06 es uno más). */
    private static int telefonosPublicados;

    @DynamicPropertySource
    static void servidores(DynamicPropertyRegistry properties) {
        properties.add("catalogo.api.url", () -> CATALOGO.baseUrl().toString());
        properties.add("catedra.api.url", () -> CATEDRA.baseUrl().toString());
        properties.add("catedra.kafka.bootstrap-servers", KafkaTestBroker::bootstrapServers);
        properties.add("catedra.kafka.actions-topic", () -> ACCIONES);
        properties.add("catedra.kafka.phone-topic", () -> TELEFONO);
        properties.add("catedra.kafka.consumer-group", () -> GRUPO);
        properties.add("catedra.kafka.group-id", () -> "proyecto-flujos");
    }

    @AfterAll
    static void detener() {
        CATALOGO.close();
        CATEDRA.close();
    }

    @MockitoBean
    private Clock clock;

    @MockitoSpyBean
    private PhoneSubmissionPublisher productor;

    @MockitoSpyBean
    private ReservationProcessRepository procesos;

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
    private String reservationProcessId;

    @BeforeEach
    void preparar() {
        when(clock.instant()).thenReturn(Instant.parse("2026-12-14T12:30:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        ReservationTestDatabase.limpiar(tx, em);
        ana = tx.execute(status -> userRepository.saveAndFlush(usuario("reserva-flujos")).getId());
        // Cada test con su propio reservationProcessId: la cátedra los genera únicos.
        reservationProcessId = "process-" + UUID.randomUUID();
        CATALOGO.reset();
        CATEDRA.reset();
        CATALOGO.respondJson(200, AGENDA);
        CATEDRA.routeJson(
            "/api/appointment-holds",
            201,
            "{\"holdId\":\"hold-flujo\",\"reservationProcessId\":\"" + reservationProcessId + "\",\"status\":\"HELD\",\"expiresAt\":\"2026-12-14T12:40:00Z\"}"
        );
        CATEDRA.routeJson(
            "/confirm",
            202,
            "{\"reservationProcessId\":\"" + reservationProcessId + "\",\"status\":\"WAITING_FOR_PHONE\",\"expiresAt\":\"2026-12-14T12:40:00Z\"}"
        );
    }

    @AfterEach
    void limpiar() {
        reset(productor, procesos);
        ReservationTestDatabase.limpiar(tx, em);
    }

    /** CU-04 → AdditionalInformationRequested → CU-06 → AppointmentConfirmed: la reserva termina CONFIRMED. */
    @Test
    void unaReservaQueTerminaConfirmada() throws Exception {
        String id = reservar();
        assertThat(estado(id)).isEqualTo("WAITING_FOR_PHONE_REQUEST");

        String pedido = pedirTelefono();
        assertThat(estado(id)).isEqualTo("PHONE_REQUESTED");

        enviarTelefono(id, "+54 9 261 555-1234").andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PHONE_SUBMITTED"));
        ConsumerRecord<String, String> enviado = ultimoTelefonoPublicado();
        assertThat(enviado.key()).isEqualTo(reservationProcessId);
        assertThat(JsonPath.<String>read(enviado.value(), "$.requestEventId")).isEqualTo(pedido);
        String eventoEnviado = JsonPath.read(enviado.value(), "$.eventId");

        publicarAccion(confirmado(eventoNuevo(), pedido));

        assertThat(estado(id)).isEqualTo("CONFIRMED");
        assertThat(columna(id, "reservation_id::text || '|' || last_submitted_event_id")).isEqualTo("8251|" + eventoEnviado);
    }

    /** CU-04 → AdditionalInformationRequested → (nadie informa el teléfono) → AppointmentProcessExpired: EXPIRED. */
    @Test
    void unaReservaQueVenceSinInformarElTelefono() throws Exception {
        String id = reservar();
        String pedido = pedirTelefono();

        publicarAccion(
            accion("AppointmentProcessExpired", eventoNuevo(), ",\"requestEventId\":\"" + pedido + "\",\"reason\":\"HOLD_EXPIRED\",\"occurredAt\":\"2026-12-14T12:40:02Z\"")
        );

        assertThat(estado(id)).isEqualTo("EXPIRED");
        assertThat(columna(id, "closing_reason")).isEqualTo("HOLD_EXPIRED");
        // Un proceso vencido no bloquea: el usuario puede volver a reservar.
        CATEDRA.routeJson(
            "/api/appointment-holds",
            201,
            "{\"holdId\":\"hold-otro\",\"reservationProcessId\":\"process-otro-" + UUID.randomUUID() + "\",\"status\":\"HELD\",\"expiresAt\":\"2026-12-14T12:45:00Z\"}"
        );
        reservarOtraVez().andExpect(status().isAccepted());
    }

    /** Un rechazo no es final: se reenvía con un eventId nuevo y el mismo requestEventId, y después se confirma. */
    @Test
    void unRechazoSeguidoDeUnReenvioConEventIdNuevo() throws Exception {
        String id = reservar();
        String pedido = pedirTelefono();
        enviarTelefono(id, "2615551234").andExpect(status().isAccepted());
        String primerEnvio = JsonPath.read(ultimoTelefonoPublicado().value(), "$.eventId");

        publicarAccion(
            accion(
                "AdditionalInformationRejected",
                eventoNuevo(),
                ",\"requestEventId\":\"" + pedido + "\",\"reason\":\"INVALID_PHONE_NUMBER\",\"message\":\"No valido.\",\"expiresAt\":\"2026-12-14T12:42:00Z\""
            )
        );
        assertThat(estado(id)).isEqualTo("PHONE_REJECTED");

        enviarTelefono(id, "+5492615559999").andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PHONE_SUBMITTED"));
        ConsumerRecord<String, String> reenvio = ultimoTelefonoPublicado();
        String segundoEnvio = JsonPath.read(reenvio.value(), "$.eventId");
        assertThat(segundoEnvio).isNotEqualTo(primerEnvio);
        assertThat(JsonPath.<String>read(reenvio.value(), "$.requestEventId")).isEqualTo(pedido);

        publicarAccion(confirmado(eventoNuevo(), pedido));
        assertThat(estado(id)).isEqualTo("CONFIRMED");
        assertThat(columna(id, "last_submitted_event_id")).isEqualTo(segundoEnvio);
    }

    /**
     * Orden A: AppointmentConfirmed se procesa <strong>entre la publicación del teléfono y el guardado</strong> de
     * PHONE_SUBMITTED. CU-06 relee, no retrocede y guarda igual el eventId enviado.
     */
    @Test
    void cu06Contra07_elResultadoLlegaAntesDeQueCu06Guarde() throws Exception {
        String id = reservar();
        String pedido = pedirTelefono();
        doAnswer(invocacion -> {
            invocacion.callRealMethod();
            publicarAccion(confirmado(eventoNuevo(), pedido));
            return null;
        })
            .when(productor)
            .publish(any());

        enviarTelefono(id, "2615551234").andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("CONFIRMED"));

        String eventoEnviado = JsonPath.read(ultimoTelefonoPublicado().value(), "$.eventId");
        assertThat(estado(id)).isEqualTo("CONFIRMED");
        assertThat(columna(id, "last_submitted_event_id")).isEqualTo(eventoEnviado);
    }

    /**
     * Orden B: CU-06 guarda PHONE_SUBMITTED <strong>mientras el listener aplica la confirmación</strong>: el listener pierde
     * por {@code @Version}, el DefaultErrorHandler lo reintenta y el segundo intento confirma sobre PHONE_SUBMITTED. El
     * guardado de CU-06 se hace por otra conexión, en el medio del guardado del listener.
     */
    @Test
    void cu06Contra07_cu06GuardaMientrasElListenerConfirma() throws Exception {
        String id = reservar();
        String pedido = pedirTelefono();
        boolean[] yaChoco = { false };
        doAnswer(invocacion -> {
            if (!yaChoco[0] && Thread.currentThread().getName().contains("ntainer")) {
                yaChoco[0] = true;
                ejecutarEnOtraConexion(
                    "update reservation_process set status = 'PHONE_SUBMITTED', phone_number = '+5492615551234'," +
                        " last_submitted_event_id = 'enviado-por-cu06', version = version + 1"
                );
            }
            return invocacion.callRealMethod();
        })
            .when(AopTestUtils.<ReservationProcessRepository>getUltimateTargetObject(procesos))
            .save(any());

        publicarAccion(confirmado(eventoNuevo(), pedido));

        assertThat(yaChoco[0]).isTrue();
        assertThat(estado(id)).isEqualTo("CONFIRMED");
        assertThat(columna(id, "last_submitted_event_id")).isEqualTo("enviado-por-cu06");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Ayudas
    // ---------------------------------------------------------------------------------------------------------------

    private String reservar() throws Exception {
        String respuesta = reservarOtraVez().andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(respuesta, "$.id");
    }

    private ResultActions reservarOtraVez() throws Exception {
        return mvc.perform(
            post("/api/reservation-processes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(TURNO)
                .header(HttpHeaders.AUTHORIZATION, ReservationTestTokens.usuario(jwtKey, "reserva-flujos", ana))
        );
    }

    /** La cátedra pide el teléfono. Devuelve el eventId del pedido. */
    private String pedirTelefono() {
        String pedido = "request-" + UUID.randomUUID();
        publicarAccion(
            accion(
                "AdditionalInformationRequested",
                pedido,
                ",\"requiredField\":\"PHONE_NUMBER\",\"message\":\"Ingrese un numero de telefono para continuar.\",\"expiresAt\":\"2026-12-14T12:40:00Z\""
            )
        );
        return pedido;
    }

    private ResultActions enviarTelefono(String id, String telefono) throws Exception {
        ResultActions respuesta = mvc.perform(
            post("/api/reservation-processes/{id}/phone", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"" + telefono + "\"}")
                .header(HttpHeaders.AUTHORIZATION, ReservationTestTokens.usuario(jwtKey, "reserva-flujos", ana))
        );
        if (respuesta.andReturn().getResponse().getStatus() == 202) {
            telefonosPublicados++;
        }
        return respuesta;
    }

    private String confirmado(String eventId, String pedido) {
        return accion(
            "AppointmentConfirmed",
            eventId,
            ",\"requestEventId\":\"" + pedido + "\",\"reservationId\":8251,\"professionalId\":202,\"date\":\"2026-12-15\"," +
                "\"startTime\":\"10:00\",\"status\":\"CONFIRMED\",\"occurredAt\":\"2026-12-14T12:36:01Z\""
        );
    }

    /** Un evento del topic de acciones para el proceso de este test. Si los campos no traen {@code occurredAt}, se agrega uno. */
    private String accion(String tipo, String eventId, String campos) {
        String ocurrio = campos.contains("occurredAt") ? "" : ",\"occurredAt\":\"2026-12-14T12:35:10Z\"";
        return (
            "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + tipo + "\",\"groupId\":\"proyecto-flujos\",\"reservationProcessId\":\"" +
            reservationProcessId + "\",\"holdId\":\"hold-flujo\"" + campos + ocurrio + ",\"schemaVersion\":1}"
        );
    }

    /** Publica en el topic de acciones, con clave = reservationProcessId, y espera a que el listener confirme el offset. */
    private void publicarAccion(String valor) {
        KafkaTestBroker.publish(ACCIONES, reservationProcessId, valor);
        accionesPublicadas++;
        await().atMost(MAX_ESPERA).until(() -> Long.valueOf(accionesPublicadas).equals(KafkaTestBroker.committedOffset(GRUPO, ACCIONES)));
    }

    /** El último teléfono publicado por este test. El topic es de toda la clase: se cuentan los envíos aceptados. */
    private ConsumerRecord<String, String> ultimoTelefonoPublicado() {
        List<ConsumerRecord<String, String>> deEsteTest = KafkaTestBroker.read(TELEFONO, telefonosPublicados)
            .stream()
            .filter(registro -> reservationProcessId.equals(registro.key()))
            .toList();
        assertThat(deEsteTest).isNotEmpty();
        return deEsteTest.getLast();
    }

    private String estado(String id) {
        return columna(id, "status");
    }

    private String columna(String id, String expresion) {
        return tx.execute(status ->
            (String) em
                .createNativeQuery("select " + expresion + " from reservation_process where id = cast(:id as uuid)")
                .setParameter("id", id)
                .getSingleResult()
        );
    }

    private static String eventoNuevo() {
        return "event-" + UUID.randomUUID();
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
