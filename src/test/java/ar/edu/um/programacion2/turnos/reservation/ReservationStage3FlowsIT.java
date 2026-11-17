package ar.edu.um.programacion2.turnos.reservation;

import static ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.ReservationTestDatabase.usuario;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.um.programacion2.turnos.IntegrationTest;
import ar.edu.um.programacion2.turnos.repository.UserRepository;
import ar.edu.um.programacion2.turnos.reservation.application.service.ReservationService;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReconciliationReport;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.client.FakeHttpServer;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.ReservationTestDatabase;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Los flujos de la etapa 3 que son de turnos (evidencias del enunciado §11), de punta a punta: los controllers, el
 * listener de Kafka y PostgreSQL reales; el catálogo y la cátedra simulados por HTTP, y el topic de acciones de
 * Testcontainers. Completa {@code ReservationFlowsIT} (confirmada, vencida, rechazo con reenvío y las carreras) con lo
 * que agregó el #14: la consulta del proceso (CU-08), "Mis reservas" (CU-09), la cancelación (CU-10) y la reconciliación
 * (CU-11).
 * <p>
 * El reloj está fijo en el lunes 14/12/2026 a las 09:30 de Mendoza (12:30 UTC); el turno es el martes 15 a las 10:00.
 */
@AutoConfigureMockMvc
@IntegrationTest
@TestPropertySource(properties = "catedra.kafka.listener-enabled=true")
class ReservationStage3FlowsIT {

    private static final String ACCIONES = KafkaTestBroker.createTopic("alumnos.turnos.acciones.etapa3-");
    private static final String TELEFONO = KafkaTestBroker.createTopic("catedra.turnos.telefono.etapa3-");
    private static final String GRUPO = "alumnos-etapa3-turnos";
    private static final FakeHttpServer CATALOGO = iniciar();
    private static final FakeHttpServer CATEDRA = iniciar();
    private static final Duration MAX_ESPERA = Duration.ofSeconds(30);
    private static final Instant AHORA = Instant.parse("2026-12-14T12:30:00Z");

    private static final String AGENDA = """
        {"catalogVersion":7,
         "professional":{"id":202,"firstName":"Ana","lastName":"Gomez","enabled":true,"category":{"id":1,"name":"Clinica","enabled":true}},
         "weeklySchedules":[{"id":1,"dayOfWeek":"TUESDAY","startTime":"09:00:00","endTime":"11:00:00","slotDurationMinutes":30}]}
        """;
    private static final String TURNO = "{\"professionalId\":202,\"date\":\"2026-12-15\",\"startTime\":\"10:00\"}";

    private static long accionesPublicadas;

    @DynamicPropertySource
    static void servidores(DynamicPropertyRegistry properties) {
        properties.add("catalogo.api.url", () -> CATALOGO.baseUrl().toString());
        properties.add("catedra.api.url", () -> CATEDRA.baseUrl().toString());
        properties.add("catedra.kafka.bootstrap-servers", KafkaTestBroker::bootstrapServers);
        properties.add("catedra.kafka.actions-topic", () -> ACCIONES);
        properties.add("catedra.kafka.phone-topic", () -> TELEFONO);
        properties.add("catedra.kafka.consumer-group", () -> GRUPO);
        properties.add("catedra.kafka.group-id", () -> "proyecto-etapa3");
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
    private ReservationService servicio;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private EntityManager em;

    @Value("${jhipster.security.authentication.jwt.base64-secret}")
    private String jwtKey;

    private long ana;
    private long beto;
    private String reservationProcessId;

    @BeforeEach
    void preparar() {
        when(clock.instant()).thenReturn(AHORA);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        ReservationTestDatabase.limpiar(tx, em);
        ana = tx.execute(status -> userRepository.saveAndFlush(usuario("reserva-ana")).getId());
        beto = tx.execute(status -> userRepository.saveAndFlush(usuario("reserva-beto")).getId());
        CATALOGO.reset();
        CATEDRA.reset();
        CATALOGO.respondJson(200, AGENDA);
        holdNuevo();
    }

    @AfterEach
    void limpiar() {
        ReservationTestDatabase.limpiar(tx, em);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // §11: reserva confirmada, consulta y cancelación propia
    // ---------------------------------------------------------------------------------------------------------------

    /** Confirmada → la app la ve por CU-08 → aparece en CU-09 → se cancela con CU-10 → AppointmentCancelled llega tarde. */
    @Test
    void confirmadaConsultadaListadaYCanceladaConElEventoTardioSinEfecto() throws Exception {
        String id = confirmada(ana);

        consultar(id, ana).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED")).andExpect(jsonPath("$.confirmation.confirmedAt").value("2026-12-14T12:36:01Z"));

        catedraLista(reserva(reservationProcessId, "user-" + ana, "CONFIRMED", null));
        misReservas(ana)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.catedraUpToDate").value(true))
            .andExpect(jsonPath("$.processes", hasSize(1)))
            .andExpect(jsonPath("$.processes[0].id").value(id))
            .andExpect(jsonPath("$.processes[0].status").value("CONFIRMED"));

        CATEDRA.routeJson(
            "/cancel",
            200,
            "{\"reservationId\":8251,\"reservationProcessId\":\"" + reservationProcessId + "\",\"status\":\"CANCELLED\",\"cancelledAt\":\"2026-12-14T12:50:00Z\"}"
        );
        cancelar(id, ana, "{\"reason\":\"No llego\"}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        String version = columna(id, "version::text");

        // La cátedra publica AppointmentCancelled después: llega tarde y queda sin efecto (el eventId sí queda registrado).
        String evento = eventoNuevo();
        publicarAccion(cancelado(evento, "Cancelado en la catedra"));

        assertThat(columna(id, "status || '|' || cancellation_reason || '|' || version::text")).isEqualTo("CANCELLED|No llego|" + version);
        assertThat(eventoRegistrado(evento)).isTrue();
        consultar(id, ana).andExpect(jsonPath("$.cancellation.reason").value("No llego"));
    }

    /** La que expira, vista por CU-08 como EXPIRED con su motivo, y el usuario vuelve a reservar. */
    @Test
    void unaReservaQueExpiraLaAppLaVeExpiradaYSePuedeVolverAReservar() throws Exception {
        String id = reservar(ana);
        String pedido = pedirTelefono();
        consultar(id, ana).andExpect(jsonPath("$.status").value("PHONE_REQUESTED")).andExpect(jsonPath("$.expiresAt").value("2026-12-14T12:40:00Z"));

        publicarAccion(accion("AppointmentProcessExpired", eventoNuevo(), ",\"requestEventId\":\"" + pedido + "\",\"reason\":\"HOLD_EXPIRED\""));

        consultar(id, ana).andExpect(jsonPath("$.status").value("EXPIRED")).andExpect(jsonPath("$.closing.reason").value("HOLD_EXPIRED"));
        holdNuevo();
        reservarOtraVez(ana).andExpect(status().isAccepted());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // §11: idempotencia (duplicado y tardío)
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void unAppointmentConfirmedDespuesDeExpiredNoLoReabreYUnDuplicadoNoRepiteEfectos() throws Exception {
        String id = reservar(ana);
        String pedido = pedirTelefono();
        String vencimiento = eventoNuevo();
        publicarAccion(accion("AppointmentProcessExpired", vencimiento, ",\"requestEventId\":\"" + pedido + "\",\"reason\":\"HOLD_EXPIRED\""));
        String version = columna(id, "version::text");

        // Tardío: la confirmación llega después del vencimiento.
        publicarAccion(confirmado(eventoNuevo(), pedido));
        // Duplicado: el mismo eventId del vencimiento otra vez (entrega al menos una vez).
        publicarAccion(accion("AppointmentProcessExpired", vencimiento, ",\"requestEventId\":\"" + pedido + "\",\"reason\":\"OTRO\""));

        assertThat(columna(id, "status || '|' || closing_reason || '|' || version::text")).isEqualTo("EXPIRED|HOLD_EXPIRED|" + version);
        assertThat(columna(id, "(select count(*) from processed_event where event_id = '" + vencimiento + "')::text")).isEqualTo("1");
        consultar(id, ana).andExpect(jsonPath("$.status").value("EXPIRED")).andExpect(jsonPath("$.confirmation").doesNotExist());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // §11: sin acceso cruzado entre usuarios comunes
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void otroUsuarioNoVeNiCancelaElProcesoAunqueLaCatedraDevuelvaLaReserva() throws Exception {
        String id = confirmada(ana);

        consultar(id, beto).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESERVATION_PROCESS_NOT_FOUND"));
        cancelar(id, beto, null).andExpect(status().isNotFound());
        assertThat(CATEDRA.requestsTo("/cancel")).isEmpty();

        // Beto no tiene procesos: su lista sale vacía sin llamar a la cátedra.
        misReservas(beto).andExpect(jsonPath("$.totalElements").value(0)).andExpect(jsonPath("$.processes", hasSize(0)));

        // Y aunque tenga un proceso propio y la cátedra devuelva la reserva de Ana con su externalPatientId o con el de Ana,
        // la de Ana no aparece: la lista sale de la tabla local.
        holdNuevo();
        String deBeto = reservar(beto);
        String deAna = reservationProcessIdDe(id);
        catedraLista(reserva(deAna, "user-" + beto, "CONFIRMED", null) + "," + reserva(deAna, "user-" + ana, "CONFIRMED", null));
        misReservas(beto).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.processes[0].id").value(deBeto));
        assertThat(columna(id, "status")).isEqualTo("CONFIRMED");
        assertThat(columna(deBeto, "status")).isEqualTo("WAITING_FOR_PHONE_REQUEST");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // §8: evento perdido y CU-11
    // ---------------------------------------------------------------------------------------------------------------

    /** El resultado nunca llega por Kafka; CU-11 lo cierra preguntándole a la cátedra, y el evento, si llega, es tardío. */
    @Test
    void unResultadoQueNoLlegaPorKafkaLoCierraLaReconciliacion() throws Exception {
        String id = reservar(ana);
        String pedido = pedirTelefono();
        enviarTelefono(id, ana).andExpect(status().isAccepted());

        // Pasado expiresAt + 1 minuto, sin noticias por Kafka: la cátedra lo tiene confirmado.
        when(clock.instant()).thenReturn(Instant.parse("2026-12-14T12:41:30Z"));
        catedraLista(reserva(reservationProcessId, "user-" + ana, "CONFIRMED", null));
        ReconciliationReport reporte = servicio.reconcilePendingProcesses();

        assertThat(reporte).isEqualTo(new ReconciliationReport.Completed(1, 1, 0, 0, ReconciliationReport.Ending.FINISHED));
        consultar(id, ana).andExpect(jsonPath("$.status").value("CONFIRMED")).andExpect(jsonPath("$.confirmation.confirmedAt").value("2026-12-14T12:36:00Z"));

        // El evento perdido aparece tarde: sin efecto.
        String version = columna(id, "version::text");
        publicarAccion(confirmado(eventoNuevo(), pedido));
        assertThat(columna(id, "status || '|' || version::text")).isEqualTo("CONFIRMED|" + version);
    }

    /** La app dejó de consultar (expiresAt + 1 min) y el usuario abre "Mis reservas": el proceso vencido sin pedido de teléfono. */
    @Test
    void unPedidoDeTelefonoQueNoLlegaNuncaTerminaExpiradoPorLaReconciliacion() throws Exception {
        String id = reservar(ana);

        when(clock.instant()).thenReturn(Instant.parse("2026-12-14T12:41:30Z"));
        // La cátedra lo venció (FAILED, con reservationId: la reserva se creó al confirmar el hold).
        catedraLista(reserva(reservationProcessId, "user-" + ana, "FAILED", null));
        misReservas(ana).andExpect(jsonPath("$.processes[0].status").value("WAITING_FOR_PHONE_REQUEST"));

        servicio.reconcilePendingProcesses();

        consultar(id, ana).andExpect(jsonPath("$.status").value("EXPIRED")).andExpect(jsonPath("$.closing.reason").value("FAILED_IN_CATEDRA"));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Ayudas
    // ---------------------------------------------------------------------------------------------------------------

    private void holdNuevo() {
        reservationProcessId = "process-" + UUID.randomUUID();
        CATEDRA.routeJson(
            "/api/appointment-holds",
            201,
            "{\"holdId\":\"hold-" + UUID.randomUUID() + "\",\"reservationProcessId\":\"" + reservationProcessId + "\",\"status\":\"HELD\",\"expiresAt\":\"2026-12-14T12:40:00Z\"}"
        );
        CATEDRA.routeJson(
            "/confirm",
            202,
            "{\"reservationProcessId\":\"" + reservationProcessId + "\",\"status\":\"WAITING_FOR_PHONE\",\"expiresAt\":\"2026-12-14T12:40:00Z\"}"
        );
    }

    private String confirmada(long userId) throws Exception {
        String id = reservar(userId);
        String pedido = pedirTelefono();
        enviarTelefono(id, userId).andExpect(status().isAccepted());
        publicarAccion(confirmado(eventoNuevo(), pedido));
        assertThat(columna(id, "status")).isEqualTo("CONFIRMED");
        return id;
    }

    private String reservar(long userId) throws Exception {
        return JsonPath.read(reservarOtraVez(userId).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private ResultActions reservarOtraVez(long userId) throws Exception {
        return mvc.perform(post("/api/reservation-processes").contentType(MediaType.APPLICATION_JSON).content(TURNO).header(HttpHeaders.AUTHORIZATION, token(userId)));
    }

    private ResultActions enviarTelefono(String id, long userId) throws Exception {
        return mvc.perform(
            post("/api/reservation-processes/{id}/phone", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"2615551234\"}")
                .header(HttpHeaders.AUTHORIZATION, token(userId))
        );
    }

    private ResultActions consultar(String id, long userId) throws Exception {
        return mvc.perform(get("/api/reservation-processes/{id}", id).header(HttpHeaders.AUTHORIZATION, token(userId)));
    }

    private ResultActions misReservas(long userId) throws Exception {
        return mvc.perform(get("/api/reservation-processes").header(HttpHeaders.AUTHORIZATION, token(userId)));
    }

    private ResultActions cancelar(String id, long userId, String cuerpo) throws Exception {
        var pedido = post("/api/reservation-processes/{id}/cancel", id).header(HttpHeaders.AUTHORIZATION, token(userId));
        return mvc.perform(cuerpo == null ? pedido : pedido.contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private String token(long userId) {
        return ReservationTestTokens.usuario(jwtKey, userId == ana ? "reserva-ana" : "reserva-beto", userId);
    }

    private String pedirTelefono() {
        String pedido = "request-" + UUID.randomUUID();
        publicarAccion(
            accion("AdditionalInformationRequested", pedido, ",\"requiredField\":\"PHONE_NUMBER\",\"message\":\"Ingrese un telefono.\",\"expiresAt\":\"2026-12-14T12:40:00Z\"")
        );
        return pedido;
    }

    private String confirmado(String eventId, String pedido) {
        return accion(
            "AppointmentConfirmed",
            eventId,
            ",\"requestEventId\":\"" + pedido + "\",\"reservationId\":8251,\"status\":\"CONFIRMED\",\"occurredAt\":\"2026-12-14T12:36:01Z\""
        );
    }

    private String cancelado(String eventId, String motivo) {
        return accion("AppointmentCancelled", eventId, ",\"reservationId\":8251,\"status\":\"CANCELLED\",\"reason\":\"" + motivo + "\",\"occurredAt\":\"2026-12-14T12:50:30Z\"");
    }

    private String accion(String tipo, String eventId, String campos) {
        String ocurrio = campos.contains("occurredAt") ? "" : ",\"occurredAt\":\"2026-12-14T12:35:10Z\"";
        return (
            "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + tipo + "\",\"groupId\":\"proyecto-etapa3\",\"reservationProcessId\":\"" +
            reservationProcessId + "\"" + campos + ocurrio + ",\"schemaVersion\":1}"
        );
    }

    private void publicarAccion(String valor) {
        KafkaTestBroker.publish(ACCIONES, reservationProcessId, valor);
        accionesPublicadas++;
        await().atMost(MAX_ESPERA).until(() -> Long.valueOf(accionesPublicadas).equals(KafkaTestBroker.committedOffset(GRUPO, ACCIONES)));
    }

    /** La cátedra responde la lista de reservas con esos ítems (una página, con su {@code X-Total-Count}). */
    private static void catedraLista(String items) {
        int total = items.split("\"reservationProcessId\"", -1).length - 1;
        CATEDRA.route("/api/appointments", 200, "[" + items + "]", Map.of("X-Total-Count", String.valueOf(total)));
    }

    private static String reserva(String processId, String externalPatientId, String estado, String cancelledAt) {
        return """
            {"reservationId":8251,"reservationProcessId":"%s","externalPatientId":"%s","appointmentDate":"2026-12-15","startTime":"10:00:00",
             "patientFirstName":"Ana","patientLastName":"Perez","patientPhone":"+5492615551234","status":"%s",
             "confirmedAt":"2026-12-14T12:36:00Z","cancelledAt":%s,"cancellationReason":null}""".formatted(
                processId,
                externalPatientId,
                estado,
                cancelledAt == null ? "null" : "\"" + cancelledAt + "\""
            );
    }

    private String reservationProcessIdDe(String id) {
        return columna(id, "reservation_process_id");
    }

    private String columna(String id, String expresion) {
        return tx.execute(status ->
            String.valueOf(
                em.createNativeQuery("select " + expresion + " from reservation_process where id = cast(:id as uuid)").setParameter("id", id).getSingleResult()
            )
        );
    }

    private boolean eventoRegistrado(String eventId) {
        return tx.execute(status ->
            ((Number) em.createNativeQuery("select count(*) from processed_event where event_id = :id").setParameter("id", eventId).getSingleResult()).intValue() == 1
        );
    }

    private static String eventoNuevo() {
        return "event-" + UUID.randomUUID();
    }

    private static FakeHttpServer iniciar() {
        try {
            return FakeHttpServer.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
