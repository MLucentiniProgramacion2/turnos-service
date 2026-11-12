package ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.um.programacion2.turnos.reservation.domain.model.ProfessionalAgenda;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ProfessionalAgenda.AgendaCategory;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ProfessionalAgenda.AgendaProfessional;
import ar.edu.um.programacion2.turnos.reservation.domain.model.WeeklySchedule;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatalogAccessDeniedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatalogInvalidResponseException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatalogUnavailableException;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.client.FakeHttpServer;
import ar.edu.um.programacion2.turnos.security.ServiceTokenIssuer;
import ar.edu.um.programacion2.turnos.security.ServiceTokenIssuerTestSupport;
import java.io.IOException;
import java.net.URI;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** {@code GET /api/service/professionals/{id}/agenda} contra un catálogo simulado (contrato del #35 del catálogo). */
class CatalogoAgendaClientTest {

    /** Copiado tal cual de {@code ProfessionalAgendaControllerIT} del catálogo: es el JSON exacto que responde. */
    private static final String AGENDA_15 =
        """
        {
          "catalogVersion": 57,
          "professional": {
            "id": 15, "firstName": "Ana", "lastName": "Gomez", "enabled": true,
            "category": {"id": 1, "name": "Clínica", "enabled": true}
          },
          "weeklySchedules": [
            {"id": 23, "dayOfWeek": "MONDAY", "startTime": "08:00:00", "endTime": "09:00:00", "slotDurationMinutes": 15},
            {"id": 21, "dayOfWeek": "MONDAY", "startTime": "09:00:00", "endTime": "12:00:00", "slotDurationMinutes": 30},
            {"id": 24, "dayOfWeek": "FRIDAY", "startTime": "09:00:00", "endTime": "13:00:30", "slotDurationMinutes": 30}
          ]
        }
        """;

    /** El del contrato del endpoint para un profesional deshabilitado (CU-07 4b del catálogo). */
    private static final String AGENDA_16_DESHABILITADO =
        """
        { "catalogVersion": 57, "professional": { "id": 16, "firstName": "Luis", "lastName": "Perez", "enabled": false, "category": { "id": 1, "name": "Clínica", "enabled": true } }, "weeklySchedules": [] }
        """;

    private FakeHttpServer catalogo;
    private ServiceTokenIssuer emisor;
    private CatalogoAgendaClient client;

    @BeforeEach
    void startCatalogo() throws IOException {
        catalogo = FakeHttpServer.start();
        emisor = ServiceTokenIssuerTestSupport.emisorFijo();
        client = cliente(catalogo.baseUrl());
    }

    @AfterEach
    void stopCatalogo() {
        catalogo.close();
    }

    private CatalogoAgendaClient cliente(URI url) {
        CatalogoApiProperties properties = new CatalogoApiProperties();
        properties.setUrl(url);
        properties.setReadTimeout(Duration.ofMillis(500));
        return new CatalogoAgendaClient(new CatalogoClientConfiguration().catalogoRestClient(properties), emisor);
    }

    @Test
    void traduceLaAgendaDelCatalogoAlDominio() {
        catalogo.respondJson(200, AGENDA_15);

        Optional<ProfessionalAgenda> agenda = client.fetchAgenda(15, "ana.perez");

        assertThat(agenda).contains(
            new ProfessionalAgenda(
                57,
                new AgendaProfessional(15, "Ana", "Gomez", true, new AgendaCategory(1, "Clínica", true)),
                List.of(
                    new WeeklySchedule(23, DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(9, 0), 15),
                    new WeeklySchedule(21, DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0), 30),
                    new WeeklySchedule(24, DayOfWeek.FRIDAY, LocalTime.of(9, 0), LocalTime.of(13, 0, 30), 30)
                )
            )
        );
    }

    @Test
    void unProfesionalDeshabilitadoVieneSinHorarios() {
        catalogo.respondJson(200, AGENDA_16_DESHABILITADO);

        ProfessionalAgenda agenda = client.fetchAgenda(16, null).orElseThrow();

        assertThat(agenda.professional().enabled()).isFalse();
        assertThat(agenda.weeklySchedules()).isEmpty();
    }

    @Test
    void aceptaHorasHHmmYHorariosQueNoGeneranTurnos() {
        catalogo.respondJson(
            200,
            AGENDA_15.replace("\"08:00:00\", \"endTime\": \"09:00:00\", \"slotDurationMinutes\": 15", "\"08:00\", \"endTime\": \"07:00\", \"slotDurationMinutes\": 0")
        );

        WeeklySchedule primero = client.fetchAgenda(15, null).orElseThrow().weeklySchedules().getFirst();

        assertThat(primero.startTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(primero.endTime()).isEqualTo(LocalTime.of(7, 0));
        assertThat(primero.slotDurationMinutes()).isZero();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // El pedido: token de servicio y X-User-Login
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void pideLaAgendaConUnTokenDeServicioNuevo() {
        catalogo.respondJson(200, AGENDA_15);

        client.fetchAgenda(15, "ana.perez");

        FakeHttpServer.ReceivedRequest pedido = catalogo.onlyRequest();
        assertThat(pedido.method()).isEqualTo("GET");
        assertThat(pedido.path()).isEqualTo("/api/service/professionals/15/agenda");
        assertThat(pedido.authorization()).isEqualTo("Bearer " + emisor.issue());
        assertThat(ServiceTokenIssuerTestSupport.esTokenDeServicio(pedido.authorization().substring("Bearer ".length()))).isTrue();
    }

    @Test
    void unLoginValidoVaEnXUserLogin() {
        catalogo.respondJson(200, AGENDA_15);

        client.fetchAgenda(15, "ana.perez@example.com");

        assertThat(catalogo.onlyRequest().userLogin()).isEqualTo("ana.perez@example.com");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
        strings = {
            "ab",
            "ana perez",
            "anaé",
            "0123456789012345678901234567890123456789012345678901",
            "ana/../x",
        }
    )
    void unLoginAusenteOInvalidoNoSeManda(String login) {
        catalogo.respondJson(200, AGENDA_15);

        client.fetchAgenda(15, login);

        assertThat(catalogo.onlyRequest().userLogin()).isNull();
    }

    @Test
    void unLoginDe3Y50CaracteresSeManda() {
        assertThat(CatalogoAgendaClient.isValidLogin("abc")).isTrue();
        assertThat(CatalogoAgendaClient.isValidLogin("a".repeat(50))).isTrue();
        assertThat(CatalogoAgendaClient.isValidLogin("a".repeat(51))).isFalse();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Errores del catálogo
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void unProfesionalQueNoExisteDaVacio() {
        catalogo.respondProblem(404, "PROFESSIONAL_NOT_FOUND");

        assertThat(client.fetchAgenda(999, null)).isEmpty();
    }

    /** Un 404 sin el code del contrato no es "no existe": probablemente la URL del catálogo está mal configurada. */
    @Test
    void un404SinElCodeDelContratoEsInvalido() {
        catalogo.respondWithoutBody(404);

        assertThatThrownBy(() -> client.fetchAgenda(15, null)).isInstanceOf(CatalogInvalidResponseException.class);
    }

    @Test
    void elCatalogoSinDatosEsNoDisponibleConSuRetryAfter() {
        catalogo.respond(
            503,
            "application/problem+json",
            "{\"status\":503,\"code\":\"CATALOG_NOT_INITIALIZED\"}",
            Map.of("Retry-After", "60")
        );

        assertThatThrownBy(() -> client.fetchAgenda(15, null)).isInstanceOfSatisfying(CatalogUnavailableException.class, e ->
            assertThat(e.retryAfter()).contains(Duration.ofSeconds(60))
        );
    }

    @Test
    void elCatalogoSinDatosYSinRetryAfterEsNoDisponibleSinPlazo() {
        catalogo.respondProblem(503, "CATALOG_NOT_INITIALIZED");

        assertThatThrownBy(() -> client.fetchAgenda(15, null)).isInstanceOfSatisfying(CatalogUnavailableException.class, e ->
            assertThat(e.retryAfter()).isEmpty()
        );
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = { "60|PT1M", "0|PT0S", "7200|PT1H", "Wed, 21 Oct 2026 07:28:00 GMT|", "-5|", "1.5|", "''|" }
    )
    void elRetryAfterSeLeeEnSegundosConTopeDeUnaHora(String header, Duration esperado) {
        assertThat(CatalogoAgendaClient.retryAfter(header)).isEqualTo(esperado);
    }

    @ParameterizedTest
    @ValueSource(ints = { 401, 403 })
    void unRechazoDelTokenDeServicioEsAccesoDenegado(int status) {
        catalogo.respondWithoutBody(status);

        assertThatThrownBy(() -> client.fetchAgenda(15, null)).isInstanceOf(CatalogAccessDeniedException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = { 408, 429, 500, 502 })
    void unaFallaTransitoriaEsNoDisponible(int status) {
        catalogo.respondWithoutBody(status);

        assertThatThrownBy(() -> client.fetchAgenda(15, null)).isInstanceOf(CatalogUnavailableException.class);
    }

    @Test
    void unCatalogoQueNoRespondeATiempoEsNoDisponible() {
        catalogo.readBodyThenHang();

        assertThatThrownBy(() -> client.fetchAgenda(15, null)).isInstanceOf(CatalogUnavailableException.class);
    }

    @Test
    void unCatalogoApagadoEsNoDisponible() throws IOException {
        client = cliente(FakeHttpServer.closedPortUrl());

        assertThatThrownBy(() -> client.fetchAgenda(15, null)).isInstanceOf(CatalogUnavailableException.class);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = {
            "JSON roto                 | {\"catalogVersion\": 57,                                    | no es el JSON esperado",
            "sin catalogVersion        | \"catalogVersion\": 57,=                                     | Falta catalogVersion",
            "otro profesional          | \"id\": 15, \"firstName\"=\"id\": 99, \"firstName\"          | no es el pedido",
            "día inválido              | FRIDAY=VIERNES                                               | dayOfWeek no es MONDAY a SUNDAY",
            "hora sin cero             | \"09:00:00\", \"endTime\": \"12:00:00\"=\"9:00\", \"endTime\": \"12:00:00\" | startTime no es una hora",
            "hora con fracción         | \"13:00:30\"=\"13:00:30.5\"                                  | endTime no es una hora",
            "sin enabled               | \"enabled\": true,\\n    \"category\"=\"category\"           | Falta professional.enabled",
            "deshabilitado con horarios| \"Gomez\", \"enabled\": true=\"Gomez\", \"enabled\": false   | deshabilitado",
            "duración con decimales    | \"slotDurationMinutes\": 15=\"slotDurationMinutes\": 15.5     | no es el JSON esperado",
        }
    )
    void unaAgendaQueNoRespetaElContratoEsInvalida(String caso, String cambio, String esperado) {
        String cuerpo = cambio.contains("=") && !cambio.startsWith("{")
            ? AGENDA_15.replace(cambio.substring(0, cambio.indexOf('=')).replace("\\n", "\n"), cambio.substring(cambio.indexOf('=') + 1))
            : cambio;
        assertThat(cuerpo).as("el cambio tiene que aplicarse").isNotEqualTo(AGENDA_15);
        catalogo.respondJson(200, cuerpo);

        assertThatThrownBy(() -> client.fetchAgenda(15, null))
            .isInstanceOf(CatalogInvalidResponseException.class)
            .hasMessageContaining(esperado);
    }

    @Test
    void unaRedireccionNoSeSigue() throws IOException {
        try (FakeHttpServer otroHost = FakeHttpServer.start()) {
            catalogo.redirectTo(otroHost.baseUrl() + "/api/service/professionals/15/agenda");

            assertThatThrownBy(() -> client.fetchAgenda(15, null)).isInstanceOf(CatalogInvalidResponseException.class);
            assertThat(otroHost.requests()).isEmpty();
        }
    }
}
