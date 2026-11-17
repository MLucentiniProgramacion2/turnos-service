package ar.edu.um.programacion2.turnos.reservation.infrastructure.web;

import static ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.ReservationTestDatabase.usuario;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.mapper.ReservationProcessMapper;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/** CU-10 de punta a punta: JWT real, PostgreSQL real y la cátedra como servidor HTTP simulado. */
@AutoConfigureMockMvc
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class CancelReservationControllerIT {

    private static final String CANCEL = "/cancel";
    private static final String MOTIVO = "Me surgió un viaje imprevisto";
    private static final String OK =
        "{\"reservationId\":8251,\"reservationProcessId\":\"process-a92b47d0-1\",\"status\":\"CANCELLED\",\"cancelledAt\":\"2026-12-16T09:00:00Z\"}";
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

    @Value("${jhipster.security.authentication.jwt.base64-secret}")
    private String jwtKey;

    private long ana;
    private long beto;

    @BeforeEach
    void preparar() {
        ReservationTestDatabase.limpiar(tx, em);
        CATEDRA.reset();
        CATEDRA.respondJson(200, OK);
        ana = tx.execute(status -> userRepository.saveAndFlush(usuario("reserva-ana")).getId());
        beto = tx.execute(status -> userRepository.saveAndFlush(usuario("reserva-beto")).getId());
    }

    @AfterEach
    void limpiar() {
        ReservationTestDatabase.limpiar(tx, em);
    }

    @Test
    void cu10_principal_200ConElProcesoCanceladoYElMotivoDelUsuario(CapturedOutput salida) throws Exception {
        UUID id = guardar(ReservationProcessStatus.CONFIRMED, ana, 1);

        String respuesta = cancelar(id, ana, "{\"reason\":\"" + MOTIVO + "\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id.toString()))
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.cancellation.reason").value(MOTIVO))
            .andExpect(jsonPath("$.cancellation.cancelledAt").value("2026-12-16T09:00:00Z"))
            .andExpect(jsonPath("$.confirmation.confirmedAt").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(respuesta).doesNotContain("process-a92b47d0", "8251", "hold-");
        assertThat(columna(id, "status")).isEqualTo("CANCELLED");
        assertThat(columna(id, "cancellation_reason")).isEqualTo(MOTIVO);
        FakeHttpServer.ReceivedRequest pedido = CATEDRA.onlyRequest();
        assertThat(pedido.path()).isEqualTo("/api/appointments/process-a92b47d0-1/cancel");
        assertThat(pedido.body()).isEqualTo("{\"reason\":\"" + MOTIVO + "\"}");
        // El motivo lo escribe el usuario: va a la cátedra y a la respuesta, nunca al log.
        assertThat(salida.getAll()).doesNotContain(MOTIVO).contains("CONFIRMED -> CANCELLED");
    }

    @Test
    void cu10_sinCuerpoOSinMotivoSeCancelaSinMotivo() throws Exception {
        UUID uno = guardar(ReservationProcessStatus.CONFIRMED, ana, 1);
        cancelar(uno, ana, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.cancellation.reason").doesNotExist());
        assertThat(CATEDRA.onlyRequest().body()).isEmpty();

        CATEDRA.reset();
        CATEDRA.respondJson(200, OK.replace("a92b47d0-1", "a92b47d0-2"));
        UUID dos = guardar(ReservationProcessStatus.CONFIRMED, ana, 2);
        cancelar(dos, ana, "{}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cu10_2a_sinTokenEs401ConElTokenDeServicio403() throws Exception {
        UUID id = guardar(ReservationProcessStatus.CONFIRMED, ana, 1);

        mvc.perform(post("/api/reservation-processes/{id}/cancel", id)).andExpect(status().isUnauthorized());
        mvc
            .perform(post("/api/reservation-processes/{id}/cancel", id).header(HttpHeaders.AUTHORIZATION, ReservationTestTokens.servicio(jwtKey)))
            .andExpect(status().isForbidden());
        assertThat(CATEDRA.requests()).isEmpty();
    }

    @Test
    void cu10_2b_unMotivoDeMasDe500CaracteresEs400SinRepetirloYSinLlamarALaCatedra() throws Exception {
        UUID id = guardar(ReservationProcessStatus.CONFIRMED, ana, 1);
        String largo = "motivo-largo-" + "x".repeat(490);

        cancelar(id, ana, "{\"reason\":\"" + largo + "\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.fieldErrors[0].field").value("reason"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("motivo-largo"))));
        assertThat(CATEDRA.requests()).isEmpty();
        assertThat(columna(id, "status")).isEqualTo("CONFIRMED");
    }

    @Test
    void cu10_3a_elProcesoDeOtroUsuarioEs404TambienParaUnAdministrador() throws Exception {
        UUID deBeto = guardar(ReservationProcessStatus.CONFIRMED, beto, 1);

        cancelar(deBeto, ana, null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESERVATION_PROCESS_NOT_FOUND"));
        mvc
            .perform(
                post("/api/reservation-processes/{id}/cancel", deBeto).header(HttpHeaders.AUTHORIZATION, ReservationTestTokens.admin(jwtKey, "reserva-ana", ana))
            )
            .andExpect(status().isNotFound());
        cancelar(UUID.randomUUID(), ana, null).andExpect(status().isNotFound());
        assertThat(CATEDRA.requests()).isEmpty();
        assertThat(columna(deBeto, "status")).isEqualTo("CONFIRMED");
    }

    @Test
    void cu10_4a_yaCanceladoEs200SinLlamarALaCatedra() throws Exception {
        UUID id = guardar(ReservationProcessStatus.CANCELLED, ana, 1);

        cancelar(id, ana, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(CATEDRA.requests()).isEmpty();
    }

    @Test
    void cu10_4b_unProcesoEnCursoEs409ConElEstadoActual() throws Exception {
        UUID id = guardar(ReservationProcessStatus.PHONE_REQUESTED, ana, 1);

        cancelar(id, ana, null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("RESERVATION_NOT_CANCELLABLE"))
            .andExpect(jsonPath("$.status").value("PHONE_REQUESTED"));
        assertThat(CATEDRA.requests()).isEmpty();
    }

    @Test
    void cu10_5a_siLaCatedraNoRespondeBienEs503YElProcesoSigueConfirmado() throws Exception {
        UUID id = guardar(ReservationProcessStatus.CONFIRMED, ana, 1);
        CATEDRA.respondWithoutBody(503);

        cancelar(id, ana, null)
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("CANCELLATION_OUTCOME_UNKNOWN"))
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Mis reservas")));
        assertThat(columna(id, "status")).isEqualTo("CONFIRMED");
        // Sin reintentos: el usuario está esperando.
        assertThat(CATEDRA.requests()).hasSize(1);
    }

    @Test
    void cu10_6a_siLaCatedraDiceQueNoEsCancelableEs409ConElEstadoLocal() throws Exception {
        UUID id = guardar(ReservationProcessStatus.CONFIRMED, ana, 1);
        CATEDRA.respondProblem(409, "RESERVATION_NOT_CANCELLABLE");

        cancelar(id, ana, null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("RESERVATION_NOT_CANCELLABLE"))
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("detalle libre"))));
        assertThat(columna(id, "status")).isEqualTo("CONFIRMED");
    }

    @Test
    void cu10_6b_unaInconsistenciaEsUnErrorGenericoSinElMotivoTecnico() throws Exception {
        UUID id = guardar(ReservationProcessStatus.CONFIRMED, ana, 1);

        CATEDRA.respondProblem(404, "RESERVATION_NOT_FOUND");
        cancelar(id, ana, null)
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("RESERVATION_NOT_FOUND"))));

        CATEDRA.respondProblem(403, "STUDENT_INTEGRATION_NOT_FOUND");
        cancelar(id, ana, null).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("CATEDRA_UNAVAILABLE"));

        assertThat(columna(id, "status")).isEqualTo("CONFIRMED");
    }

    @Test
    void cu10_dobleToque_dosCancelacionesSimultaneasTerminanLasDosEn200() throws Exception {
        UUID id = guardar(ReservationProcessStatus.CONFIRMED, ana, 1);
        // La cátedra retiene la primera hasta que llegue la segunda: las dos leyeron CONFIRMED y las dos piden cancelar.
        CountDownLatch llegaron = new CountDownLatch(2);
        CATEDRA.beforeResponding(CANCEL, () -> {
            llegaron.countDown();
            try {
                llegaron.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> respuestas = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                respuestas.add(hilos.submit(() -> cancelar(id, ana, null).andReturn().getResponse().getStatus()));
            }

            for (Future<Integer> respuesta : respuestas) {
                assertThat(respuesta.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            hilos.shutdownNow();
        }
        assertThat(llegaron.getCount()).isZero();
        assertThat(CATEDRA.requests()).hasSize(2);
        assertThat(columna(id, "status")).isEqualTo("CANCELLED");
        assertThat(columna(id, "version")).isEqualTo("1");
    }

    // ---------------------------------------------------------------------------------------------------------------

    private ResultActions cancelar(UUID id, long userId, String cuerpo) throws Exception {
        String login = userId == ana ? "reserva-ana" : "reserva-beto";
        MockHttpServletRequestBuilder pedido = post("/api/reservation-processes/{id}/cancel", id).header(
            HttpHeaders.AUTHORIZATION,
            ReservationTestTokens.usuario(jwtKey, login, userId)
        );
        if (cuerpo != null) {
            pedido = pedido.contentType(MediaType.APPLICATION_JSON).content(cuerpo);
        }
        return mvc.perform(pedido);
    }

    private UUID guardar(ReservationProcessStatus estado, long userId, int n) {
        return tx.execute(status -> procesos.insert(mapper.toDomain(ReservationTestDatabase.fila(mapper, estado, userId, n)))).getId().value();
    }

    private String columna(UUID id, String columna) {
        return tx.execute(status ->
            String.valueOf(
                em.createNativeQuery("select " + columna + " from reservation_process where id = cast(? as uuid)").setParameter(1, id.toString()).getSingleResult()
            )
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
