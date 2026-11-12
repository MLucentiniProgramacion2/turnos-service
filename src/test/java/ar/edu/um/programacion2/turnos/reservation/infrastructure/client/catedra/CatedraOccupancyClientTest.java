package ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catedra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.um.programacion2.turnos.reservation.domain.model.Occupancy;
import ar.edu.um.programacion2.turnos.reservation.domain.model.OccupancyReport;
import ar.edu.um.programacion2.turnos.reservation.domain.model.OccupancyStatus;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraAccessDeniedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraErrorCode;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraInvalidResponseException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraRejectedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraUnavailableException;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.client.FakeHttpServer;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** {@code GET /api/appointment-occupancies} contra una cátedra simulada (contrato §8 y §13.1). */
class CatedraOccupancyClientTest {

    static final String TOKEN = "token-de-prueba-1a2b3c";
    private static final LocalDate DIA = LocalDate.parse("2026-12-14");
    private static final LocalDate HASTA = LocalDate.parse("2026-12-18");

    private FakeHttpServer catedra;
    private CatedraOccupancyClient client;

    @BeforeEach
    void startCatedra() throws IOException {
        catedra = FakeHttpServer.start();
        client = new CatedraOccupancyClient(restClient(catedra.baseUrl(), Duration.ofMillis(500)));
    }

    @AfterEach
    void stopCatedra() {
        catedra.close();
    }

    static org.springframework.web.client.RestClient restClient(URI url, Duration readTimeout) {
        CatedraApiProperties properties = new CatedraApiProperties();
        properties.setUrl(url);
        properties.setToken(TOKEN);
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setReadTimeout(readTimeout);
        return new CatedraClientConfiguration().catedraRestClient(properties);
    }

    /** Lo que devuelve hoy la cátedra real para el profesional 202 (11/11): sin ocupaciones. */
    @Test
    void ocupacionesVaciasComoLasDeLaCatedraReal() {
        catedra.respondJson(200, "{\"professionalId\":202,\"from\":\"2026-12-14\",\"to\":\"2026-12-18\",\"occupancies\":[]}");

        OccupancyReport report = client.fetchOccupancies(202, DIA, HASTA);

        assertThat(report.occupancies()).isEmpty();
        assertThat(report.professionalId()).isEqualTo(202);
        FakeHttpServer.ReceivedRequest pedido = catedra.onlyRequest();
        assertThat(pedido.method()).isEqualTo("GET");
        assertThat(pedido.path()).isEqualTo("/api/appointment-occupancies");
        assertThat(pedido.query()).isEqualTo("professionalId=202&from=2026-12-14&to=2026-12-18");
        assertThat(pedido.authorization()).isEqualTo("Bearer " + TOKEN);
    }

    /** El ejemplo del contrato §8, con un día que es {@code from} y {@code to} a la vez. */
    @Test
    void ocupacionesHeldYConfirmedDelEjemploDelContrato() {
        catedra.respondJson(
            200,
            """
            {"professionalId": 15, "from": "2026-09-14", "to": "2026-09-14", "occupancies": [
              {"date": "2026-09-14", "startTime": "09:00:00", "status": "CONFIRMED", "expiresAt": null},
              {"date": "2026-09-14", "startTime": "09:30:00", "status": "HELD", "expiresAt": "2026-09-10T14:44:50Z"}
            ]}
            """
        );
        LocalDate dia = LocalDate.parse("2026-09-14");

        OccupancyReport report = client.fetchOccupancies(15, dia, dia);

        assertThat(report.occupancies()).containsExactly(
            new Occupancy(dia, LocalTime.of(9, 0), OccupancyStatus.CONFIRMED, null),
            new Occupancy(dia, LocalTime.of(9, 30), OccupancyStatus.HELD, Instant.parse("2026-09-10T14:44:50Z"))
        );
    }

    @Test
    void aceptaHorasHHmmYHHmmssYCamposDesconocidos() {
        catedra.respondJson(
            200,
            """
            {"professionalId": 202, "from": "2026-12-14", "to": "2026-12-18", "nuevo": true, "occupancies": [
              {"date": "2026-12-14", "startTime": "09:00", "status": "HELD", "expiresAt": "2026-12-10T12:00:00Z", "otro": 1},
              {"date": "2026-12-15", "startTime": "13:00:30", "status": "CONFIRMED"}
            ]}
            """
        );

        assertThat(client.fetchOccupancies(202, DIA, HASTA).occupancies())
            .extracting(Occupancy::startTime)
            .containsExactly(LocalTime.of(9, 0), LocalTime.of(13, 0, 30));
    }

    @Test
    void aceptaUnaUrlBaseConBarraFinal() {
        client = new CatedraOccupancyClient(restClient(URI.create(catedra.baseUrl() + "/"), Duration.ofMillis(500)));
        catedra.respondJson(200, "{\"professionalId\":202,\"from\":\"2026-12-14\",\"to\":\"2026-12-18\",\"occupancies\":[]}");

        client.fetchOccupancies(202, DIA, HASTA);

        assertThat(catedra.onlyRequest().path()).isEqualTo("/api/appointment-occupancies");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Rechazos y acceso
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void unRangoInvalidoEsUnRechazoConSuCodeYSinElDetail() {
        catedra.respondProblem(400, "INVALID_DATE_RANGE");

        assertThatThrownBy(() -> client.fetchOccupancies(202, DIA, HASTA))
            .isInstanceOfSatisfying(CatedraRejectedException.class, e -> {
                assertThat(e.status()).isEqualTo(400);
                assertThat(e.code()).isEqualTo(CatedraErrorCode.INVALID_DATE_RANGE);
            })
            .hasMessageContaining("code=INVALID_DATE_RANGE")
            .hasMessageNotContaining("detalle libre");
    }

    @ParameterizedTest
    @ValueSource(ints = { 401, 403 })
    void unRechazoDeLaCredencialSinCuerpoEsAccesoDenegado(int status) {
        catedra.respondWithoutBody(status);

        assertThatThrownBy(() -> client.fetchOccupancies(202, DIA, HASTA))
            .isInstanceOf(CatedraAccessDeniedException.class)
            .hasMessageContaining("(sin code)");
    }

    @Test
    void sinIntegracionTecnicaEsAccesoDenegado() {
        catedra.respondProblem(403, "STUDENT_INTEGRATION_NOT_FOUND");

        assertThatThrownBy(() -> client.fetchOccupancies(202, DIA, HASTA)).isInstanceOf(CatedraAccessDeniedException.class);
    }

    @Test
    void unCodeConFormatoRaroNoSeEscribeEnElMensaje() {
        catedra.respondProblem(400, "INYECTADO\\nCON SALTO");

        assertThatThrownBy(() -> client.fetchOccupancies(202, DIA, HASTA))
            .isInstanceOfSatisfying(CatedraRejectedException.class, e -> assertThat(e.code()).isEqualTo(CatedraErrorCode.UNRECOGNIZED))
            .hasMessageContaining("code con formato inválido")
            .hasMessageNotContaining("INYECTADO");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Fallas transitorias: es una consulta, así que nunca son "resultado desconocido"
    // ---------------------------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = { 408, 429, 500, 502, 503, 504 })
    void unaFallaTransitoriaEsNoDisponible(int status) {
        catedra.respondProblem(status, "CUALQUIERA");

        assertThatThrownBy(() -> client.fetchOccupancies(202, DIA, HASTA)).isInstanceOf(CatedraUnavailableException.class);
    }

    @Test
    void unServidorApagadoEsNoDisponible() throws IOException {
        client = new CatedraOccupancyClient(restClient(FakeHttpServer.closedPortUrl(), Duration.ofMillis(500)));

        assertThatThrownBy(() -> client.fetchOccupancies(202, DIA, HASTA))
            .isInstanceOf(CatedraUnavailableException.class)
            .hasMessageContaining("No se pudo conectar");
    }

    @Test
    void unaCatedraQueNoRespondeATiempoEsNoDisponible() {
        catedra.readBodyThenHang();

        assertThatThrownBy(() -> client.fetchOccupancies(202, DIA, HASTA)).isInstanceOf(CatedraUnavailableException.class);
    }

    @Test
    void unCuerpoQueSeCortaAMitadEsNoDisponibleYNoInvalido() {
        catedra.cutInTheMiddleOfTheBody(200, "{\"professionalId\":202,\"from\":\"2026-12-14\",\"to\":\"2026-12-18\",\"occupancies\":[]}");

        assertThatThrownBy(() -> client.fetchOccupancies(202, DIA, HASTA)).isInstanceOf(CatedraUnavailableException.class);
    }

    /**
     * Medido en el #10: si el servidor cierra sin responder, el cliente del JDK repite el {@code GET} una vez por su
     * cuenta. No se desactiva (es una consulta sin efectos); este test fija el comportamiento para que un cambio de JDK
     * se note.
     */
    @Test
    void siElServidorCierraSinResponderElJdkRepiteElGetUnaVez() {
        catedra.closeWithoutResponding();

        assertThatThrownBy(() -> client.fetchOccupancies(202, DIA, HASTA)).isInstanceOf(CatedraUnavailableException.class);
        assertThat(catedra.requests()).hasSize(2);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Respuestas que no respetan el contrato
    // ---------------------------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = {
            "JSON roto                     | {\"professionalId\":202,                                                                                  | no es el JSON esperado",
            "cuerpo null                   | null                                                                                                       | Falta el cuerpo",
            "sin occupancies               | {\"professionalId\":202,\"from\":\"2026-12-14\",\"to\":\"2026-12-18\"}                                    | Falta occupancies",
            "otro profesional              | {\"professionalId\":203,\"from\":\"2026-12-14\",\"to\":\"2026-12-18\",\"occupancies\":[]}                  | professionalId no es el pedido",
            "id con decimales              | {\"professionalId\":202.5,\"from\":\"2026-12-14\",\"to\":\"2026-12-18\",\"occupancies\":[]}                | no es el JSON esperado",
            "otro rango                    | {\"professionalId\":202,\"from\":\"2026-12-15\",\"to\":\"2026-12-18\",\"occupancies\":[]}                  | from o to no son los pedidos",
            "estado desconocido            | {\"professionalId\":202,\"from\":\"2026-12-14\",\"to\":\"2026-12-18\",\"occupancies\":[{\"date\":\"2026-12-14\",\"startTime\":\"09:00\",\"status\":\"PENDING\"}]}   | no es HELD ni CONFIRMED",
            "hora sin cero                 | {\"professionalId\":202,\"from\":\"2026-12-14\",\"to\":\"2026-12-18\",\"occupancies\":[{\"date\":\"2026-12-14\",\"startTime\":\"9:00\",\"status\":\"HELD\"}]}       | startTime no es una hora",
            "hora 24                       | {\"professionalId\":202,\"from\":\"2026-12-14\",\"to\":\"2026-12-18\",\"occupancies\":[{\"date\":\"2026-12-14\",\"startTime\":\"24:00\",\"status\":\"HELD\"}]}      | startTime no es una hora",
            "hora con fracción             | {\"professionalId\":202,\"from\":\"2026-12-14\",\"to\":\"2026-12-18\",\"occupancies\":[{\"date\":\"2026-12-14\",\"startTime\":\"09:00:00.5\",\"status\":\"HELD\"}]} | startTime no es una hora",
            "fecha fuera del rango         | {\"professionalId\":202,\"from\":\"2026-12-14\",\"to\":\"2026-12-18\",\"occupancies\":[{\"date\":\"2026-12-20\",\"startTime\":\"09:00\",\"status\":\"HELD\"}]}      | fuera del rango",
            "vencimiento inválido          | {\"professionalId\":202,\"from\":\"2026-12-14\",\"to\":\"2026-12-18\",\"occupancies\":[{\"date\":\"2026-12-14\",\"startTime\":\"09:00\",\"status\":\"HELD\",\"expiresAt\":\"mañana\"}]} | expiresAt no es un instante",
        }
    )
    void unCuerpoQueNoRespetaElContratoEsInvalido(String caso, String cuerpo, String esperado) {
        catedra.respondJson(200, cuerpo);

        assertThatThrownBy(() -> client.fetchOccupancies(202, DIA, HASTA))
            .isInstanceOf(CatedraInvalidResponseException.class)
            .hasMessageContaining(esperado);
    }

    @ParameterizedTest
    @ValueSource(ints = { 201, 204, 404 })
    void unStatusQueNoEstaEnElContratoEsInvalido(int status) {
        catedra.respondWithoutBody(status);

        assertThatThrownBy(() -> client.fetchOccupancies(202, DIA, HASTA))
            .isInstanceOf(CatedraInvalidResponseException.class)
            .hasMessageContaining("status inesperado");
    }

    @Test
    void unaRedireccionNoSeSigueYEsInvalida() throws IOException {
        try (FakeHttpServer otroHost = FakeHttpServer.start()) {
            catedra.redirectTo(otroHost.baseUrl() + "/api/appointment-occupancies");

            assertThatThrownBy(() -> client.fetchOccupancies(202, DIA, HASTA)).isInstanceOf(CatedraInvalidResponseException.class);
            assertThat(otroHost.requests()).isEmpty();
        }
    }

    /** El token y la URL nunca en el mensaje de una excepción: los mensajes pueden llegar a un log o a una respuesta. */
    @Test
    void losMensajesNoTienenElTokenNiLaUrl() {
        for (Runnable configurar : new Runnable[] {
            () -> catedra.respondWithoutBody(401),
            () -> catedra.respondProblem(503, "X"),
            () -> catedra.respondJson(200, "{roto"),
            catedra::readBodyThenHang,
        }) {
            configurar.run();
            assertThatThrownBy(() -> client.fetchOccupancies(202, DIA, HASTA))
                .isInstanceOf(CatedraException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(TOKEN).doesNotContain(catedra.baseUrl().toString()));
        }
    }
}
