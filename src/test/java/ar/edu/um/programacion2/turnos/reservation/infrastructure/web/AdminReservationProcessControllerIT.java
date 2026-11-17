package ar.edu.um.programacion2.turnos.reservation.infrastructure.web;

import static ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.ReservationTestDatabase.usuario;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.um.programacion2.turnos.IntegrationTest;
import ar.edu.um.programacion2.turnos.repository.UserRepository;
import ar.edu.um.programacion2.turnos.reservation.ReservationTestTokens;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ReservationProcessRepository;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.ReservationTestDatabase;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.entity.ReservationProcessEntity;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.mapper.ReservationProcessMapper;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/** CU-12 de punta a punta: JWT real y PostgreSQL real. Solo datos locales: no hace falta simular la cátedra. */
@AutoConfigureMockMvc
@IntegrationTest
class AdminReservationProcessControllerIT {

    private static final String URL = "/api/admin/reservation-processes";

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
    private long admin;
    private UUID deAna;
    private UUID deBeto;

    @BeforeEach
    void preparar() {
        ReservationTestDatabase.limpiar(tx, em);
        ana = tx.execute(status -> userRepository.saveAndFlush(usuario("reserva-ana")).getId());
        beto = tx.execute(status -> userRepository.saveAndFlush(usuario("reserva-beto")).getId());
        admin = tx.execute(status -> userRepository.saveAndFlush(usuario("reserva-admin")).getId());
        deAna = guardar(ReservationProcessStatus.CANCELLED, ana, 1, "2026-12-10T11:00:00Z");
        deBeto = guardar(ReservationProcessStatus.PHONE_REJECTED, beto, 2, "2026-12-10T11:30:00Z");
    }

    @AfterEach
    void limpiar() {
        ReservationTestDatabase.limpiar(tx, em);
    }

    @Test
    void cu12_principal_unAdministradorVeLosProcesosDeTodosConLosIdentificadoresDeDiagnostico() throws Exception {
        String respuesta = listar("", ReservationTestTokens.admin(jwtKey, "reserva-admin", admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.processes", hasSize(2)))
            // Lo último que se movió primero.
            .andExpect(jsonPath("$.processes[0].id").value(deBeto.toString()))
            .andExpect(jsonPath("$.processes[0].owner.userId").value(beto))
            .andExpect(jsonPath("$.processes[0].owner.loginAtStart").value("ana.perez"))
            .andExpect(jsonPath("$.processes[0].status").value("PHONE_REJECTED"))
            .andExpect(jsonPath("$.processes[0].statusChangedAt").value("2026-12-10T11:30:00Z"))
            .andExpect(jsonPath("$.processes[0].integration.holdId").value("hold-6c0c2f41-2"))
            .andExpect(jsonPath("$.processes[0].integration.reservationProcessId").value("process-a92b47d0-2"))
            .andExpect(jsonPath("$.processes[0].integration.requestEventId").value("request-event-8a31"))
            .andExpect(jsonPath("$.processes[0].integration.lastSubmittedEventId").value("phone-submitted-b91e"))
            .andExpect(jsonPath("$.processes[0].rejection.reason").value("INVALID_PHONE_NUMBER"))
            .andExpect(jsonPath("$.processes[1].integration.reservationId").value(8251))
            .andExpect(jsonPath("$.processes[1].cancellation.withReason").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();

        // Ni el teléfono, ni los nombres del paciente (Juan Perez en la matriz), ni el texto del motivo de cancelación.
        assertThat(respuesta).doesNotContain("5492615551234", "Juan", "Perez", "Cambio solicitado", "phoneNumber", "patient");
    }

    @Test
    void cu12_2a_2b_matrizDeAutorizacion() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mvc.perform(get(URL + "/" + deAna)).andExpect(status().isUnauthorized());
        listar("", ReservationTestTokens.usuario(jwtKey, "reserva-ana", ana)).andExpect(status().isForbidden());
        mvc.perform(get(URL + "/" + deAna).header(HttpHeaders.AUTHORIZATION, ReservationTestTokens.usuario(jwtKey, "reserva-ana", ana))).andExpect(status().isForbidden());
        listar("", ReservationTestTokens.servicio(jwtKey)).andExpect(status().isForbidden());
        listar("", ReservationTestTokens.admin(jwtKey, "reserva-admin", admin)).andExpect(status().isOk());
    }

    @Test
    void cu12_soloLectura_noHayEndpointsQueModifiquen() throws Exception {
        String token = ReservationTestTokens.admin(jwtKey, "reserva-admin", admin);
        mvc.perform(post(URL + "/" + deAna + "/cancel").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isNotFound());
        mvc.perform(delete(URL + "/" + deAna).header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void cu12_1_filtrosPorDuenoYPorEstado() throws Exception {
        String token = ReservationTestTokens.admin(jwtKey, "reserva-admin", admin);

        listar("?userId=" + ana, token).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.processes[0].id").value(deAna.toString()));
        listar("?status=PHONE_REJECTED", token).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.processes[0].id").value(deBeto.toString()));
        listar("?userId=" + ana + "&status=PHONE_REJECTED", token).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void cu12_3a_ningunoCumpleLosFiltrosEsUnaListaVacia() throws Exception {
        listar("?userId=999999", ReservationTestTokens.admin(jwtKey, "reserva-admin", admin))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0))
            .andExpect(jsonPath("$.processes", hasSize(0)));
    }

    @Test
    void cu12_5_elDetalleDeCualquierDuenoY5aUnoQueNoExisteEs404() throws Exception {
        String token = ReservationTestTokens.admin(jwtKey, "reserva-admin", admin);

        mvc
            .perform(get(URL + "/" + deBeto).header(HttpHeaders.AUTHORIZATION, token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.owner.userId").value(beto))
            .andExpect(jsonPath("$.integration.holdId").value("hold-6c0c2f41-2"));
        mvc
            .perform(get(URL + "/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, token))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESERVATION_PROCESS_NOT_FOUND"));
    }

    @Test
    void parametrosInvalidosSon400SinRepetirElValor() throws Exception {
        String token = ReservationTestTokens.admin(jwtKey, "reserva-admin", admin);
        for (String parametros : new String[] { "?userId=0", "?userId=abc<b>", "?status=OTRO<b>", "?size=101" }) {
            String cuerpo = listar(parametros, token)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andReturn()
                .getResponse()
                .getContentAsString();
            assertThat(cuerpo).as(parametros).doesNotContain("<b>", "abc", "OTRO");
        }
    }

    @Test
    void dpt14_unAdministradorEnLosEndpointsComunesVeSoloLoSuyo() throws Exception {
        String token = ReservationTestTokens.admin(jwtKey, "reserva-admin", admin);

        mvc.perform(get("/api/reservation-processes").header(HttpHeaders.AUTHORIZATION, token)).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/reservation-processes/" + deAna).header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isNotFound());
    }

    private ResultActions listar(String parametros, String token) throws Exception {
        return mvc.perform(get(URL + parametros).header(HttpHeaders.AUTHORIZATION, token));
    }

    private UUID guardar(ReservationProcessStatus estado, long userId, int n, String ultimoCambio) {
        ReservationProcessEntity entidad = ReservationTestDatabase.fila(mapper, estado, userId, n);
        entidad.setStatusChangedAt(Instant.parse(ultimoCambio));
        return tx.execute(status -> procesos.insert(mapper.toDomain(entidad))).getId().value();
    }
}
