package ar.edu.um.programacion2.turnos.reservation.infrastructure.web;

import static ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.ReservationTestDatabase.usuario;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.um.programacion2.turnos.IntegrationTest;
import ar.edu.um.programacion2.turnos.repository.UserRepository;
import ar.edu.um.programacion2.turnos.reservation.ReservationTestTokens;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessId;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ReservationProcessRepository;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.ReservationTestDatabase;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.mapper.ReservationProcessMapper;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CU-08 de punta a punta: JWT real y PostgreSQL real. La consulta no llama a nadie más, así que no hace falta simular el
 * catálogo ni la cátedra (sus URL de test apuntan a un puerto cerrado: si la consulta los llamara, fallaría).
 */
@AutoConfigureMockMvc
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class ReservationProcessStatusControllerIT {

    private static final String URL = "/api/reservation-processes/";

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
    private UUID deAna;
    private UUID deBeto;

    @BeforeEach
    void preparar() {
        ReservationTestDatabase.limpiar(tx, em);
        ana = tx.execute(status -> userRepository.saveAndFlush(usuario("reserva-ana")).getId());
        beto = tx.execute(status -> userRepository.saveAndFlush(usuario("reserva-beto")).getId());
        deAna = guardar(ReservationProcessStatus.PHONE_REJECTED, ana, 1);
        deBeto = guardar(ReservationProcessStatus.CONFIRMED, beto, 2);
    }

    @AfterEach
    void limpiar() {
        ReservationTestDatabase.limpiar(tx, em);
    }

    @Test
    void cu08_principal_devuelveElProcesoPropioConElDtoDeCu04YSinDatosDeLaIntegracion() throws Exception {
        String respuesta = consultar(deAna, ReservationTestTokens.usuario(jwtKey, "reserva-ana", ana))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(deAna.toString()))
            .andExpect(jsonPath("$.status").value("PHONE_REJECTED"))
            .andExpect(jsonPath("$.expiresAt").isNotEmpty())
            .andExpect(jsonPath("$.appointment.startTime").value("09:00:00"))
            .andExpect(jsonPath("$.phoneRequest").exists())
            .andExpect(jsonPath("$.rejection.reason").value("INVALID_PHONE_NUMBER"))
            .andExpect(jsonPath("$.confirmation").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(respuesta).doesNotContain("hold-", "process-", "holdId", "reservationProcessId", "request-event", "phone-submitted", "5492615551234");
    }

    @Test
    void cu08_2a_sinTokenEs401YConElTokenDeServicioEs403() throws Exception {
        mvc.perform(get(URL + deAna)).andExpect(status().isUnauthorized());
        consultar(deAna, ReservationTestTokens.servicio(jwtKey)).andExpect(status().isForbidden());
    }

    @Test
    void cu08_2b_elProcesoDeOtroUsuarioEs404IgualQueUnoQueNoExiste() throws Exception {
        String ajeno = consultar(deBeto, ReservationTestTokens.usuario(jwtKey, "reserva-ana", ana))
            .andExpect(status().isNotFound())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("RESERVATION_PROCESS_NOT_FOUND"))
            .andReturn()
            .getResponse()
            .getContentAsString();
        String inexistente = consultar(UUID.randomUUID(), ReservationTestTokens.usuario(jwtKey, "reserva-ana", ana))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESERVATION_PROCESS_NOT_FOUND"))
            .andReturn()
            .getResponse()
            .getContentAsString();

        // Lo único que cambia es la ruta: ni el estado ni el dueño del ajeno.
        assertThat(ajeno.replace(deBeto.toString(), "X")).isEqualTo(inexistente.replaceAll("[0-9a-f-]{36}", "X"));
        assertThat(ajeno).doesNotContain("CONFIRMED", "reserva-beto");
    }

    @Test
    void cu08_dpt14_unAdministradorVeSoloLoSuyoEnElEndpointComun() throws Exception {
        String tokenAdminBeto = ReservationTestTokens.admin(jwtKey, "reserva-beto", beto);

        consultar(deAna, tokenAdminBeto).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESERVATION_PROCESS_NOT_FOUND"));
        consultar(deBeto, tokenAdminBeto).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void cu08_unIdQueNoEsUnUuidEs400SinRepetirElValor() throws Exception {
        // El valor aparece solo en path e instance, que son la URI del pedido (codificada), como en todo el contrato REST.
        mvc
            .perform(get(URL + "no-es-un-uuid<script>").header(HttpHeaders.AUTHORIZATION, ReservationTestTokens.usuario(jwtKey, "reserva-ana", ana)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Failed to convert 'id'"))
            .andExpect(jsonPath("$.code").doesNotExist())
            .andExpect(content().string(not(containsString("<script>"))));
    }

    @Test
    void cu08_esUnaSolaSentenciaYNoDejaNadaEnElLog(CapturedOutput salida) throws Exception {
        String token = ReservationTestTokens.usuario(jwtKey, "reserva-ana", ana);
        consultar(deAna, token).andExpect(status().isOk());
        Statistics estadisticas = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        estadisticas.setStatisticsEnabled(true);
        int antes = salida.getOut().length();
        try {
            estadisticas.clear();

            // Como la app: varias consultas seguidas, cada 2 s (DPT-04), y una de un proceso ajeno.
            for (int i = 0; i < 5; i++) {
                consultar(deAna, token).andExpect(status().isOk());
            }
            consultar(deBeto, token).andExpect(status().isNotFound());

            assertThat(estadisticas.getPrepareStatementCount()).isEqualTo(6);
        } finally {
            estadisticas.setStatisticsEnabled(false);
        }
        // El log de la aplicación queda en INFO en los tests: una consulta no escribe nada (el 404 va a DEBUG).
        assertThat(salida.getOut().substring(antes)).doesNotContain(deAna.toString()).doesNotContain(deBeto.toString()).doesNotContain("CU-08");
    }

    @Test
    void cu08_noModificaElProcesoAunqueHayaPasadoExpiresAt() throws Exception {
        // La consulta no mira el reloj ni escribe: ni la versión ni el instante del último cambio se mueven (el vencido lo cubre el unitario).
        ReservationProcess antes = tx.execute(status -> procesos.findByIdAndOwner(new ReservationProcessId(deAna), ana).orElseThrow());

        consultar(deAna, ReservationTestTokens.usuario(jwtKey, "reserva-ana", ana)).andExpect(status().isOk());

        ReservationProcess despues = tx.execute(status -> procesos.findByIdAndOwner(new ReservationProcessId(deAna), ana).orElseThrow());
        assertThat(despues.getVersion()).isEqualTo(antes.getVersion());
        assertThat(despues.getStatusChangedAt()).isEqualTo(antes.getStatusChangedAt());
    }

    private UUID guardar(ReservationProcessStatus estado, long userId, int n) {
        ReservationProcess proceso = mapper.toDomain(ReservationTestDatabase.fila(mapper, estado, userId, n));
        return tx.execute(status -> procesos.insert(proceso)).getId().value();
    }

    private ResultActions consultar(UUID id, String token) throws Exception {
        return mvc.perform(get(URL + id).header(HttpHeaders.AUTHORIZATION, token));
    }
}
