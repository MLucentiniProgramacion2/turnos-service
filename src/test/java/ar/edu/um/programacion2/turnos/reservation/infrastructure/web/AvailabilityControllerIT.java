package ar.edu.um.programacion2.turnos.reservation.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.um.programacion2.turnos.IntegrationTest;
import ar.edu.um.programacion2.turnos.reservation.ReservationTestTokens;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.client.FakeHttpServer;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * CU-03 de punta a punta: el pedido entra por la cadena de seguridad real con un JWT de usuario, y el catálogo y la cátedra
 * son dos servidores HTTP simulados. El reloj está fijo en el lunes 14/12/2026 a las 09:30 de Mendoza (12:30 UTC).
 */
@AutoConfigureMockMvc
@IntegrationTest
class AvailabilityControllerIT {

    private static final String URL = "/api/professionals/{id}/availability";
    private static final FakeHttpServer CATALOGO = iniciar();
    private static final FakeHttpServer CATEDRA = iniciar();

    private static final String AGENDA = """
        {"catalogVersion":7,
         "professional":{"id":202,"firstName":"Ana","lastName":"Gomez","enabled":true,"category":{"id":1,"name":"Clinica","enabled":true}},
         "weeklySchedules":[{"id":1,"dayOfWeek":"TUESDAY","startTime":"09:00:00","endTime":"11:00:00","slotDurationMinutes":30}]}
        """;
    private static final String AGENDA_DESHABILITADO = """
        {"catalogVersion":7,
         "professional":{"id":202,"firstName":"Ana","lastName":"Gomez","enabled":false,"category":{"id":1,"name":"Clinica","enabled":true}},
         "weeklySchedules":[]}
        """;
    /** Una hora con segundos y otra sin: el contrato (§4) admite las dos. */
    private static final String OCUPACIONES = """
        {"professionalId":202,"from":"2026-12-14","to":"2026-12-15","occupancies":[
          {"date":"2026-12-15","startTime":"09:30:00","status":"CONFIRMED","expiresAt":null},
          {"date":"2026-12-15","startTime":"10:00","status":"HELD","expiresAt":"2026-12-14T12:40:00Z"}]}
        """;

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

    @Value("${jhipster.security.authentication.jwt.base64-secret}")
    private String jwtKey;

    @Value("${catedra.api.token}")
    private String tokenDeLaCatedra;

    private int pedidosAlCatalogo;
    private int pedidosALaCatedra;

    @BeforeEach
    void preparar() {
        when(clock.instant()).thenReturn(Instant.parse("2026-12-14T12:30:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        CATALOGO.respondJson(200, AGENDA);
        CATEDRA.respondJson(200, OCUPACIONES);
        pedidosAlCatalogo = CATALOGO.requests().size();
        pedidosALaCatedra = CATEDRA.requests().size();
    }

    @Test
    void cu03_principal_devuelveLosTurnosLibresPorDiaConLasHorasConSegundos() throws Exception {
        consultar("2026-12-14", "2026-12-15")
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(
                content()
                    .json(
                        """
                        {"professional":{"id":202,"firstName":"Ana","lastName":"Gomez","category":{"id":1,"name":"Clinica","enabled":true}},
                         "from":"2026-12-14","to":"2026-12-15","timeZone":"America/Argentina/Mendoza",
                         "days":[{"date":"2026-12-14","slots":[]},
                                 {"date":"2026-12-15","slots":[{"startTime":"09:00:00","endTime":"09:30:00"},
                                                                {"startTime":"10:30:00","endTime":"11:00:00"}]}]}
                        """,
                        org.springframework.test.json.JsonCompareMode.STRICT
                    )
            );

        FakeHttpServer.ReceivedRequest alCatalogo = CATALOGO.requests().getLast();
        assertThat(alCatalogo.path()).isEqualTo("/api/service/professionals/202/agenda");
        assertThat(alCatalogo.userLogin()).isEqualTo("reserva-ana");
        assertThat(alCatalogo.authorization()).startsWith("Bearer ").doesNotContain(tokenDeLaCatedra);
        FakeHttpServer.ReceivedRequest aLaCatedra = CATEDRA.requests().getLast();
        assertThat(aLaCatedra.path()).isEqualTo("/api/appointment-occupancies");
        assertThat(aLaCatedra.query()).isEqualTo("professionalId=202&from=2026-12-14&to=2026-12-15");
        assertThat(aLaCatedra.authorization()).isEqualTo("Bearer " + tokenDeLaCatedra);
        assertThat(llamadasAlCatalogo()).isEqualTo(1);
        assertThat(llamadasALaCatedra()).isEqualTo(1);
    }

    @Test
    void cu03_2a_sinTokenEs401YConElTokenDeServicioEs403() throws Exception {
        mvc.perform(pedido("2026-12-14", "2026-12-15")).andExpect(status().isUnauthorized());
        mvc.perform(pedido("2026-12-14", "2026-12-15").header(HttpHeaders.AUTHORIZATION, "Bearer no-es-un-token")).andExpect(status().isUnauthorized());
        mvc
            .perform(pedido("2026-12-14", "2026-12-15").header(HttpHeaders.AUTHORIZATION, ReservationTestTokens.servicio(jwtKey)))
            .andExpect(status().isForbidden());

        assertThat(llamadasAlCatalogo()).isZero();
        assertThat(llamadasALaCatedra()).isZero();
    }

    @Test
    void cu03_unAdministradorTambienPuedeConsultar() throws Exception {
        mvc
            .perform(pedido("2026-12-14", "2026-12-15").header(HttpHeaders.AUTHORIZATION, ReservationTestTokens.admin(jwtKey, "reserva-admin", 1)))
            .andExpect(status().isOk());
    }

    @Test
    void cu03_2b_sinUnaFechaEsUn400DeValidacionConElCampo() throws Exception {
        mvc
            .perform(get(URL, 202).param("from", "2026-12-14").header(HttpHeaders.AUTHORIZATION, usuario()))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.fieldErrors", hasSize(1)))
            .andExpect(jsonPath("$.fieldErrors[0].field").value("to"));

        assertThat(llamadasAlCatalogo()).isZero();
    }

    @Test
    void cu03_2b_unaFechaConOtroFormatoEsUn400QueNoRepiteElValor() throws Exception {
        mvc
            .perform(get(URL, 202).param("from", "14/12/2026<script>").param("to", "2026-12-15").header(HttpHeaders.AUTHORIZATION, usuario()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.fieldErrors[0].field").value("from"))
            .andExpect(jsonPath("$.fieldErrors[0].message").value("Failed to convert value"))
            .andExpect(content().string(not(containsString("14/12"))))
            .andExpect(content().string(not(containsString("script"))))
            .andExpect(content().string(not(containsString("java."))));

        assertThat(llamadasAlCatalogo()).isZero();
    }

    @Test
    void cu03_unIdDeProfesionalQueNoEsUnNumeroEsUn400QueNoLoRepiteEnElDetail() throws Exception {
        mvc
            .perform(get(URL, "noEsUnNumero").param("from", "2026-12-14").param("to", "2026-12-15").header(HttpHeaders.AUTHORIZATION, usuario()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Failed to convert 'professionalId'"));

        assertThat(llamadasAlCatalogo()).isZero();
    }

    @Test
    void cu03_2b_unRangoQueNoCumpleDpt10Es400YNoSeLlamaANadie() throws Exception {
        consultar("2026-12-13", "2026-12-14").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
        consultar("2026-12-16", "2026-12-15").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
        // 8 días: to = from + 7.
        consultar("2026-12-14", "2026-12-21").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
        // El día 61: hoy + 61.
        consultar("2027-02-13", "2027-02-13")
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"))
            .andExpect(jsonPath("$.message").value("error.http.400"))
            .andExpect(jsonPath("$.path").value("/api/professionals/202/availability"));

        assertThat(llamadasAlCatalogo()).isZero();
        assertThat(llamadasALaCatedra()).isZero();
    }

    @Test
    void cu03_2_losBordesInclusivosDelRangoSon200() throws Exception {
        // 7 días (to = from + 6) y el día 60 (12/02/2027). El cliente exige que la cátedra responda el rango pedido.
        CATEDRA.respondJson(200, "{\"professionalId\":202,\"from\":\"2026-12-14\",\"to\":\"2026-12-20\",\"occupancies\":[]}");
        consultar("2026-12-14", "2026-12-20").andExpect(status().isOk()).andExpect(jsonPath("$.days", hasSize(7)));
        CATEDRA.respondJson(200, "{\"professionalId\":202,\"from\":\"2027-02-12\",\"to\":\"2027-02-12\",\"occupancies\":[]}");
        consultar("2027-02-12", "2027-02-12").andExpect(status().isOk()).andExpect(jsonPath("$.days", hasSize(1)));
    }

    @Test
    void cu03_3a_conElCatalogoSinDatosEs503ConElRetryAfterDelCatalogoYNoSeLlamaALaCatedra() throws Exception {
        CATALOGO.respond(
            503,
            "application/problem+json",
            "{\"status\":503,\"detail\":\"detalle libre que no se usa\",\"code\":\"CATALOG_NOT_INITIALIZED\"}",
            Map.of("Retry-After", "60")
        );

        consultar("2026-12-14", "2026-12-15")
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"))
            .andExpect(jsonPath("$.code").value("CATALOG_UNAVAILABLE"))
            .andExpect(content().string(not(containsString("detalle libre"))));

        assertThat(llamadasALaCatedra()).isZero();
    }

    @Test
    void cu03_3a_siElCatalogoRechazaElTokenDeServicioLaRespuestaNoSeDistingueDeUnaCaida() throws Exception {
        CATALOGO.respondWithoutBody(403);

        consultar("2026-12-14", "2026-12-15")
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
            .andExpect(jsonPath("$.code").value("CATALOG_UNAVAILABLE"))
            .andExpect(jsonPath("$.detail").value("El catálogo de profesionales no está disponible en este momento"));

        assertThat(llamadasALaCatedra()).isZero();
    }

    @Test
    void cu03_3_unaAgendaFueraDeContratoEs502() throws Exception {
        CATALOGO.respondJson(200, "{\"catalogVersion\":7}");

        consultar("2026-12-14", "2026-12-15").andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("UPSTREAM_INVALID_RESPONSE"));

        assertThat(llamadasALaCatedra()).isZero();
    }

    @Test
    void cu03_4a_unProfesionalQueNoExisteEs404() throws Exception {
        CATALOGO.respondProblem(404, "PROFESSIONAL_NOT_FOUND");

        consultar("2026-12-14", "2026-12-15")
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PROFESSIONAL_NOT_FOUND"))
            .andExpect(content().string(not(containsString("detalle libre"))));

        assertThat(llamadasALaCatedra()).isZero();
    }

    @Test
    void cu03_4b_unProfesionalDeshabilitadoEs400() throws Exception {
        CATALOGO.respondJson(200, AGENDA_DESHABILITADO);

        consultar("2026-12-14", "2026-12-15").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PROFESSIONAL_DISABLED"));

        assertThat(llamadasALaCatedra()).isZero();
    }

    @Test
    void cu03_5a_sinHorariosEnElRangoLaRespuestaTraeLosDiasVaciosSinLlamarALaCatedra() throws Exception {
        // El lunes 14 no tiene horarios: la agenda es de los martes.
        consultar("2026-12-14", "2026-12-14")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.days", hasSize(1)))
            .andExpect(jsonPath("$.days[0].slots", hasSize(0)));

        assertThat(llamadasALaCatedra()).isZero();
    }

    @Test
    void cu03_6a_siLaCatedraResponde5xxEs503SinReintentos() throws Exception {
        CATEDRA.respondWithoutBody(500);

        consultar("2026-12-14", "2026-12-15").andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("CATEDRA_UNAVAILABLE"));

        assertThat(llamadasALaCatedra()).isEqualTo(1);
    }

    @Test
    void cu03_6b_siLaCatedraRechazaElRangoEs500SinSuDetail() throws Exception {
        CATEDRA.respondProblem(400, "INVALID_DATE_RANGE");

        consultar("2026-12-14", "2026-12-15")
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(content().string(not(containsString("detalle libre"))))
            .andExpect(content().string(not(containsString("INVALID_DATE_RANGE"))));
    }

    @Test
    void cu03_6c_siLaCatedraRechazaLaCredencialTecnicaEs503GenericoQueNoRevelaElMotivo() throws Exception {
        CATEDRA.respondWithoutBody(401);

        consultar("2026-12-14", "2026-12-15")
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("CATEDRA_UNAVAILABLE"))
            .andExpect(jsonPath("$.detail").value("El servicio de turnos no está disponible en este momento"))
            .andExpect(content().string(not(containsString(tokenDeLaCatedra))));
    }

    @Test
    void cu03_6_unasOcupacionesFueraDeContratoSon502() throws Exception {
        CATEDRA.respondJson(200, "{\"professionalId\":202}");

        consultar("2026-12-14", "2026-12-15").andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("UPSTREAM_INVALID_RESPONSE"));
    }

    private ResultActions consultar(String desde, String hasta) throws Exception {
        return mvc.perform(pedido(desde, hasta).header(HttpHeaders.AUTHORIZATION, usuario()));
    }

    private static MockHttpServletRequestBuilder pedido(String desde, String hasta) {
        return get(URL, 202).param("from", desde).param("to", hasta);
    }

    private String usuario() {
        return ReservationTestTokens.usuario(jwtKey, "reserva-ana", 42);
    }

    private int llamadasAlCatalogo() {
        return CATALOGO.requests().size() - pedidosAlCatalogo;
    }

    private int llamadasALaCatedra() {
        return CATEDRA.requests().size() - pedidosALaCatedra;
    }

    private static FakeHttpServer iniciar() {
        try {
            return FakeHttpServer.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
