package ar.edu.um.programacion2.turnos.reservation.infrastructure.web;

import static ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.ReservationTestDatabase.usuario;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.um.programacion2.turnos.IntegrationTest;
import ar.edu.um.programacion2.turnos.repository.UserRepository;
import ar.edu.um.programacion2.turnos.reservation.KafkaTestBroker;
import ar.edu.um.programacion2.turnos.reservation.ReservationTestTokens;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessMatrix;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.ReservationTestDatabase;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.entity.ReservationProcessEntity;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.mapper.ReservationProcessMapper;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.repository.JpaReservationProcessRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CU-06 de punta a punta: JWT real, PostgreSQL real y el productor publicando en un broker real (Testcontainers). El
 * reloj está en {@link ReservationProcessMatrix#AHORA}, diez minutos antes de que venza el hold de los procesos de prueba.
 */
@AutoConfigureMockMvc
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class ReservationPhoneControllerIT {

    private static final String TOPIC = KafkaTestBroker.createTopic("catedra.turnos.telefono.test-");
    /** Un teléfono con dígitos que no aparecen en ningún otro lado: si el log los tiene, salieron de acá. */
    private static final String TELEFONO = "+54 9 (261) 444-33.22";
    private static final String DIGITOS = "4443322";

    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry properties) {
        properties.add("catedra.kafka.bootstrap-servers", KafkaTestBroker::bootstrapServers);
        properties.add("catedra.kafka.phone-topic", () -> TOPIC);
        properties.add("catedra.kafka.group-id", () -> "proyecto-de-prueba");
    }

    @MockitoBean
    private Clock clock;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JpaReservationProcessRepository jpa;

    @Autowired
    private ReservationProcessMapper mapper;

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
    private int publicadosAntes;

    @BeforeEach
    void preparar() {
        when(clock.instant()).thenReturn(ReservationProcessMatrix.AHORA);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        ReservationTestDatabase.limpiar(tx, em);
        ana = tx.execute(status -> userRepository.saveAndFlush(usuario("reserva-ana")).getId());
        beto = tx.execute(status -> userRepository.saveAndFlush(usuario("reserva-beto")).getId());
        publicadosAntes = KafkaTestBroker.read(TOPIC, 0).size();
    }

    @AfterEach
    void limpiar() {
        ReservationTestDatabase.limpiar(tx, em);
    }

    @Test
    void cu06_principal_202ConPhoneSubmittedYElEventoPublicadoConLaClaveYLosCamposDelContrato(CapturedOutput salida) throws Exception {
        UUID id = guardar(ReservationProcessStatus.PHONE_REQUESTED, ana, 1);

        enviar(id, TELEFONO, ana)
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.id").value(id.toString()))
            .andExpect(jsonPath("$.status").value("PHONE_SUBMITTED"))
            .andExpect(content().string(not(containsString(DIGITOS))));

        List<ConsumerRecord<String, String>> publicados = KafkaTestBroker.read(TOPIC, publicadosAntes + 1);
        ConsumerRecord<String, String> evento = publicados.getLast();
        assertThat(evento.key()).isEqualTo("process-a92b47d0-1");
        String eventId = tx.execute(status ->
            (String) em.createNativeQuery("select last_submitted_event_id from reservation_process").getSingleResult()
        );
        assertThat(eventId).isNotBlank();
        assertThat(evento.value()).isEqualTo(
            "{\"eventId\":\"" + eventId + "\",\"eventType\":\"AdditionalInformationSubmitted\",\"groupId\":\"proyecto-de-prueba\"," +
                "\"reservationProcessId\":\"process-a92b47d0-1\",\"holdId\":\"hold-6c0c2f41-1\",\"requestEventId\":\"request-event-8a31\"," +
                "\"information\":{\"phoneNumber\":\"+5492614443322\"},\"occurredAt\":\"2026-12-10T12:00:00Z\",\"schemaVersion\":1}"
        );
        String guardado = tx.execute(status -> (String) em.createNativeQuery("select status || '|' || phone_number from reservation_process").getSingleResult());
        assertThat(guardado).isEqualTo("PHONE_SUBMITTED|+5492614443322");
        // El log de todo el pedido, también el del productor y el de Kafka: el teléfono no aparece.
        assertThat(salida.getAll()).contains("AdditionalInformationSubmitted " + eventId + " publicado").doesNotContain(DIGITOS);
    }

    @Test
    void cu06_2a_sinTokenEs401YConElTokenDeServicioEs403() throws Exception {
        UUID id = guardar(ReservationProcessStatus.PHONE_REQUESTED, ana, 1);

        mvc.perform(post("/api/reservation-processes/{id}/phone", id).contentType(MediaType.APPLICATION_JSON).content(cuerpo(TELEFONO))).andExpect(status().isUnauthorized());
        mvc
            .perform(
                post("/api/reservation-processes/{id}/phone", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(cuerpo(TELEFONO))
                    .header(HttpHeaders.AUTHORIZATION, ReservationTestTokens.servicio(jwtKey))
            )
            .andExpect(status().isForbidden());

        assertThat(KafkaTestBroker.read(TOPIC, publicadosAntes)).hasSize(publicadosAntes);
    }

    @Test
    void cu06_2b_elProcesoDeOtroUsuarioRespondeIgualQueUnoQueNoExisteYNoSeTocaNada() throws Exception {
        UUID deAna = guardar(ReservationProcessStatus.PHONE_REQUESTED, ana, 1);

        String ajeno = enviar(deAna, TELEFONO, beto).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String inexistente = enviar(UUID.randomUUID(), TELEFONO, beto).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

        assertThat(ajeno).contains("\"code\":\"RESERVATION_PROCESS_NOT_FOUND\"");
        // Igual salvo path e instance, que son la URI del pedido.
        String sinUri = "\"(path|instance)\":\"[^\"]*\"";
        assertThat(ajeno.replaceAll(sinUri, "")).isEqualTo(inexistente.replaceAll(sinUri, ""));
        assertThat(estado()).isEqualTo("PHONE_REQUESTED");
    }

    @Test
    void cu06_unIdQueNoEsUnUuidEsUn400QueNoRepiteElValorEnElDetail() throws Exception {
        mvc
            .perform(
                post("/api/reservation-processes/{id}/phone", "noEsUnUuid")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(cuerpo(TELEFONO))
                    .header(HttpHeaders.AUTHORIZATION, ReservationTestTokens.usuario(jwtKey, "reserva-ana", ana))
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Failed to convert 'id'"));
    }

    @Test
    void cu06_3_cadaEstadoQueNoAceptaElTelefonoTieneSuCode() throws Exception {
        enviar(guardar(ReservationProcessStatus.WAITING_FOR_PHONE_REQUEST, ana, 1), TELEFONO, ana)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PHONE_NOT_REQUESTED"));
        limpiarProcesos();
        enviar(guardar(ReservationProcessStatus.PHONE_SUBMITTED, ana, 2), TELEFONO, ana)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PHONE_ALREADY_SUBMITTED"));
        enviar(guardar(ReservationProcessStatus.CONFIRMED, ana, 3), TELEFONO, ana)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("RESERVATION_PROCESS_CLOSED"))
            .andExpect(jsonPath("$.status").value("CONFIRMED"));

        assertThat(KafkaTestBroker.read(TOPIC, publicadosAntes)).hasSize(publicadosAntes);
    }

    @Test
    void cu06_3d_vencidoEs409YElProcesoNoCambia() throws Exception {
        UUID id = guardar(ReservationProcessStatus.PHONE_REJECTED, ana, 1);
        when(clock.instant()).thenReturn(ReservationProcessMatrix.VENCE);

        enviar(id, TELEFONO, ana).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESERVATION_EXPIRED"));

        assertThat(estado()).isEqualTo("PHONE_REJECTED");
    }

    @Test
    void cu06_4a_unTelefonoInvalidoEs400SinRepetirloYNoSePublicaNada(CapturedOutput salida) throws Exception {
        UUID id = guardar(ReservationProcessStatus.PHONE_REQUESTED, ana, 1);

        enviar(id, "261 444 33 22 x", ana)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_PHONE_NUMBER"))
            .andExpect(content().string(not(containsString("444"))));
        enviar(id, "+54 9 261 444 3322 1111 2222 3333 4444 5555 66", ana)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.fieldErrors[0].field").value("phoneNumber"))
            .andExpect(content().string(not(containsString("444"))));
        mvc
            .perform(
                post("/api/reservation-processes/{id}/phone", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
                    .header(HttpHeaders.AUTHORIZATION, ReservationTestTokens.usuario(jwtKey, "reserva-ana", ana))
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(estado()).isEqualTo("PHONE_REQUESTED");
        assertThat(KafkaTestBroker.read(TOPIC, publicadosAntes)).hasSize(publicadosAntes);
        assertThat(salida.getAll()).doesNotContain("4443322").doesNotContain("444 33");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Ayudas
    // ---------------------------------------------------------------------------------------------------------------

    private ResultActions enviar(UUID id, String telefono, long userId) throws Exception {
        String login = userId == ana ? "reserva-ana" : "reserva-beto";
        return mvc.perform(
            post("/api/reservation-processes/{id}/phone", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo(telefono))
                .header(HttpHeaders.AUTHORIZATION, ReservationTestTokens.usuario(jwtKey, login, userId))
        );
    }

    private static String cuerpo(String telefono) {
        return "{\"phoneNumber\":\"" + telefono + "\"}";
    }

    private UUID guardar(ReservationProcessStatus estado, long userId, int n) {
        ReservationProcessEntity fila = ReservationTestDatabase.fila(mapper, estado, userId, n);
        tx.executeWithoutResult(status -> jpa.saveAndFlush(fila));
        return fila.getId();
    }

    private void limpiarProcesos() {
        tx.executeWithoutResult(status -> em.createNativeQuery("delete from reservation_process").executeUpdate());
    }

    private String estado() {
        return tx.execute(status -> (String) em.createNativeQuery("select status from reservation_process").getSingleResult());
    }
}
