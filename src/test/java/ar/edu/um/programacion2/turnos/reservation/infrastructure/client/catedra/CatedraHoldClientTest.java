package ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catedra;

import static ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catedra.CatedraOccupancyClientTest.TOKEN;
import static ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catedra.CatedraOccupancyClientTest.restClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.um.programacion2.turnos.reservation.domain.model.AppointmentSlot;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraHold;
import ar.edu.um.programacion2.turnos.reservation.domain.model.HoldConfirmation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PatientName;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraAccessDeniedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraErrorCode;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraInvalidResponseException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraOutcomeUnknownException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraRejectedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraUnavailableException;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.client.FakeHttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.json.JsonAssert;

/**
 * {@code POST /api/appointment-holds} y {@code POST /api/appointment-holds/{holdId}/confirm} contra una cátedra simulada
 * (contrato §9, §10, §13.1 y §18.4). Son operaciones con efectos: lo que importa es distinguir "no se aplicó" de
 * "resultado desconocido".
 */
class CatedraHoldClientTest {

    private static final AppointmentSlot TURNO = new AppointmentSlot(
        15,
        LocalDate.parse("2026-09-14"),
        LocalTime.parse("09:30"),
        LocalTime.parse("10:00")
    );
    private static final PatientName PACIENTE = new PatientName("Juan", "Perez");
    private static final String HOLD_201 =
        "{\"holdId\":\"hold-6c0c2f41\",\"reservationProcessId\":\"process-a92b47d0\",\"status\":\"HELD\",\"expiresAt\":\"2026-09-10T14:44:50Z\"}";
    private static final String CONFIRM_202 =
        "{\"reservationProcessId\":\"process-a92b47d0\",\"status\":\"WAITING_FOR_PHONE\",\"expiresAt\":\"2026-09-10T14:44:50Z\"}";

    private FakeHttpServer catedra;
    private CatedraHoldClient client;

    @BeforeEach
    void startCatedra() throws IOException {
        catedra = FakeHttpServer.start();
        client = new CatedraHoldClient(restClient(catedra.baseUrl(), Duration.ofMillis(500)));
    }

    @AfterEach
    void stopCatedra() {
        catedra.close();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Éxitos
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void crearElHoldMandaElPedidoDelContratoYDevuelveSusDatos() throws Exception {
        catedra.respondJson(201, HOLD_201);

        CatedraHold hold = client.createHold(TURNO, "user-42");

        assertThat(hold).isEqualTo(new CatedraHold("hold-6c0c2f41", "process-a92b47d0", Instant.parse("2026-09-10T14:44:50Z")));
        FakeHttpServer.ReceivedRequest pedido = catedra.onlyRequest();
        assertThat(pedido.method()).isEqualTo("POST");
        assertThat(pedido.path()).isEqualTo("/api/appointment-holds");
        assertThat(pedido.authorization()).isEqualTo("Bearer " + TOKEN);
        assertThat(pedido.contentType()).startsWith("application/json");
        JsonAssert.comparator(JsonCompareMode.STRICT).assertIsMatch(
            "{\"professionalId\":15,\"date\":\"2026-09-14\",\"startTime\":\"09:30\",\"externalPatientId\":\"user-42\"}",
            pedido.body()
        );
    }

    @Test
    void unaHoraConSegundosSeMandaConSegundos() {
        catedra.respondJson(201, HOLD_201);

        client.createHold(new AppointmentSlot(15, TURNO.date(), LocalTime.parse("13:00:30"), LocalTime.parse("13:30")), "user-42");

        assertThat(catedra.onlyRequest().body()).contains("\"startTime\":\"13:00:30\"");
    }

    @Test
    void confirmarElHoldMandaElPedidoDelContratoYDevuelveSusDatos() throws Exception {
        catedra.respondJson(202, CONFIRM_202);

        HoldConfirmation confirmacion = client.confirmHold("hold-6c0c2f41", "user-42", PACIENTE);

        assertThat(confirmacion).isEqualTo(new HoldConfirmation("process-a92b47d0", Instant.parse("2026-09-10T14:44:50Z")));
        FakeHttpServer.ReceivedRequest pedido = catedra.onlyRequest();
        assertThat(pedido.method()).isEqualTo("POST");
        assertThat(pedido.path()).isEqualTo("/api/appointment-holds/hold-6c0c2f41/confirm");
        assertThat(pedido.authorization()).isEqualTo("Bearer " + TOKEN);
        JsonAssert.comparator(JsonCompareMode.STRICT).assertIsMatch(
            "{\"externalPatientId\":\"user-42\",\"patientFirstName\":\"Juan\",\"patientLastName\":\"Perez\"}",
            pedido.body()
        );
    }

    @Test
    void unHoldIdConCaracteresRarosNoCambiaLaRuta() {
        catedra.respondJson(202, CONFIRM_202);

        client.confirmHold("hold/../x?y", "user-42", PACIENTE);

        assertThat(catedra.onlyRequest().path()).isEqualTo("/api/appointment-holds/hold%2F..%2Fx%3Fy/confirm");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Rechazos funcionales (§13.1): el caso de uso decide por status y code
    // ---------------------------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "crear: {0} {1}")
    @CsvSource(
        {
            "404, PROFESSIONAL_NOT_FOUND",
            "400, PROFESSIONAL_DISABLED",
            "400, INVALID_SLOT",
            "409, SLOT_ALREADY_RESERVED",
            "409, SLOT_ALREADY_HELD",
            "400, VALIDATION_ERROR",
        }
    )
    void losRechazosAlCrearElHoldLlevanStatusYCode(int status, CatedraErrorCode code) {
        catedra.respondProblem(status, code.name());

        assertThatThrownBy(() -> client.createHold(TURNO, "user-42"))
            .isInstanceOfSatisfying(CatedraRejectedException.class, e -> {
                assertThat(e.status()).isEqualTo(status);
                assertThat(e.code()).isEqualTo(code);
            })
            .hasMessageNotContaining("detalle libre");
    }

    @ParameterizedTest(name = "confirmar: {0} {1}")
    @CsvSource(
        {
            "404, PROFESSIONAL_NOT_FOUND",
            "400, INVALID_SLOT",
            "409, SLOT_ALREADY_RESERVED",
            "404, HOLD_NOT_FOUND",
            "404, HOLD_EXPIRED",
            "403, HOLD_OWNER_MISMATCH",
            "400, EXTERNAL_PATIENT_MISMATCH",
            "409, RESERVATION_PROCESS_ALREADY_CONFIRMED",
            "400, VALIDATION_ERROR",
        }
    )
    void losRechazosAlConfirmarLlevanStatusYCode(int status, CatedraErrorCode code) {
        catedra.respondProblem(status, code.name());

        assertThatThrownBy(() -> client.confirmHold("hold-6c0c2f41", "user-42", PACIENTE)).isInstanceOfSatisfying(
            CatedraRejectedException.class,
            e -> {
                assertThat(e.status()).isEqualTo(status);
                assertThat(e.code()).isEqualTo(code);
            }
        );
    }

    @Test
    void unCodeQueNoEstaEnLaMatrizEsUnRechazoNoReconocido() {
        catedra.respondProblem(409, "ALGO_NUEVO");

        assertThatThrownBy(() -> client.createHold(TURNO, "user-42")).isInstanceOfSatisfying(CatedraRejectedException.class, e ->
            assertThat(e.code()).isEqualTo(CatedraErrorCode.UNRECOGNIZED)
        );
    }

    @Test
    void un4xxSinCodeEsUnaRespuestaInvalida() {
        catedra.respondWithoutBody(404);

        assertThatThrownBy(() -> client.confirmHold("hold-6c0c2f41", "user-42", PACIENTE)).isInstanceOf(
            CatedraInvalidResponseException.class
        );
    }

    @ParameterizedTest
    @ValueSource(ints = { 401, 403 })
    void unRechazoDeLaCredencialSinCodeEsAccesoDenegado(int status) {
        catedra.respondWithoutBody(status);

        assertThatThrownBy(() -> client.createHold(TURNO, "user-42")).isInstanceOf(CatedraAccessDeniedException.class);
    }

    @Test
    void sinIntegracionTecnicaEsAccesoDenegadoYNoUnRechazo() {
        catedra.respondProblem(403, "STUDENT_INTEGRATION_NOT_FOUND");

        assertThatThrownBy(() -> client.confirmHold("hold-6c0c2f41", "user-42", PACIENTE)).isInstanceOf(
            CatedraAccessDeniedException.class
        );
    }

    // ---------------------------------------------------------------------------------------------------------------
    // No se aplicó: no se pudo conectar
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void conLaCatedraApagadaNoSeAplicoNada() throws IOException {
        client = new CatedraHoldClient(restClient(FakeHttpServer.closedPortUrl(), Duration.ofMillis(500)));

        assertThatThrownBy(() -> client.createHold(TURNO, "user-42")).isInstanceOf(CatedraUnavailableException.class);
        assertThatThrownBy(() -> client.confirmHold("hold-6c0c2f41", "user-42", PACIENTE)).isInstanceOf(
            CatedraUnavailableException.class
        );
    }

    /**
     * Timeout de conexión: un socket que no acepta conexiones y tiene la cola llena. Linux descarta los SYN nuevos (no
     * responde con RST), así que el connect no termina y vence el plazo (medido en el #10, unos 500 ms). Es el único caso,
     * además de la conexión rechazada, en que se sabe que el pedido no salió.
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    void unTimeoutDeConexionEsQueNoSeAplicoNada() throws IOException {
        try (ServerSocket sinAceptar = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            List<Socket> relleno = new ArrayList<>();
            try {
                for (int i = 0; i < 8; i++) {
                    Socket socket = new Socket();
                    try {
                        socket.connect(sinAceptar.getLocalSocketAddress(), 200);
                        relleno.add(socket);
                    } catch (IOException colaLlena) {
                        socket.close();
                        break;
                    }
                }
                CatedraApiProperties properties = new CatedraApiProperties();
                properties.setUrl(URI.create("http://127.0.0.1:" + sinAceptar.getLocalPort()));
                properties.setToken(TOKEN);
                properties.setConnectTimeout(Duration.ofMillis(300));
                properties.setReadTimeout(Duration.ofSeconds(2));
                client = new CatedraHoldClient(new CatedraClientConfiguration().catedraRestClient(properties));

                assertThatThrownBy(() -> client.createHold(TURNO, "user-42"))
                    .isInstanceOf(CatedraUnavailableException.class)
                    .hasMessageContaining("No se pudo conectar")
                    .hasRootCauseInstanceOf(java.net.ConnectException.class);
            } finally {
                for (Socket socket : relleno) {
                    socket.close();
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Resultado desconocido: hubo conexión, la operación pudo haberse aplicado (§18.4)
    // ---------------------------------------------------------------------------------------------------------------

    static Stream<Arguments> fallasDespuesDeConectar() {
        return Stream.of(
            Arguments.of("lee el pedido y no responde", (Consumer<FakeHttpServer>) FakeHttpServer::readBodyThenHang),
            Arguments.of("no lee el pedido ni responde", (Consumer<FakeHttpServer>) FakeHttpServer::hangWithoutReading),
            Arguments.of("cierra sin responder", (Consumer<FakeHttpServer>) FakeHttpServer::closeWithoutResponding),
            Arguments.of("201 cortado a la mitad", (Consumer<FakeHttpServer>) s -> s.cutInTheMiddleOfTheBody(201, HOLD_201)),
            Arguments.of("408", (Consumer<FakeHttpServer>) s -> s.respondProblem(408, "X")),
            Arguments.of("429", (Consumer<FakeHttpServer>) s -> s.respondProblem(429, "X")),
            Arguments.of("500", (Consumer<FakeHttpServer>) s -> s.respondWithoutBody(500)),
            Arguments.of("503", (Consumer<FakeHttpServer>) s -> s.respondProblem(503, "X")),
            Arguments.of("200 en vez de 201", (Consumer<FakeHttpServer>) s -> s.respondJson(200, HOLD_201)),
            Arguments.of("201 con JSON roto", (Consumer<FakeHttpServer>) s -> s.respondJson(201, "{\"holdId\":")),
            Arguments.of("201 sin holdId", (Consumer<FakeHttpServer>) s ->
                s.respondJson(201, "{\"reservationProcessId\":\"p\",\"status\":\"HELD\",\"expiresAt\":\"2026-09-10T14:44:50Z\"}")
            ),
            Arguments.of("201 con otro status", (Consumer<FakeHttpServer>) s -> s.respondJson(201, HOLD_201.replace("HELD", "EXPIRED")))
        );
    }

    /**
     * Ningún caso se reintenta: el {@code POST} llega una sola vez, aunque el servidor cierre la conexión (con un
     * {@code GET}, el cliente del JDK sí lo repetiría, ver {@code CatedraOccupancyClientTest}).
     */
    @ParameterizedTest(name = "crear: {0}")
    @MethodSource("fallasDespuesDeConectar")
    void crearElHoldConUnaFallaDespuesDeConectarEsResultadoDesconocidoYNoSeRepite(String caso, Consumer<FakeHttpServer> falla) {
        falla.accept(catedra);

        assertThatThrownBy(() -> client.createHold(TURNO, "user-42"))
            .isInstanceOf(CatedraOutcomeUnknownException.class)
            .hasMessageContaining("resultado desconocido");
        assertThat(catedra.requests()).hasSize(1);
    }

    @ParameterizedTest(name = "confirmar: {0}")
    @CsvSource({ "WAITING_FOR_PHONE, EXPIRED", "process-a92b47d0, ''" })
    void confirmarConUn202QueNoRespetaElContratoEsResultadoDesconocido(String reemplazar, String por) {
        catedra.respondJson(202, CONFIRM_202.replace(reemplazar, por));

        assertThatThrownBy(() -> client.confirmHold("hold-6c0c2f41", "user-42", PACIENTE)).isInstanceOf(
            CatedraOutcomeUnknownException.class
        );
    }

    @Test
    void confirmarSinRespuestaATiempoEsResultadoDesconocido() {
        catedra.readBodyThenHang();

        assertThatThrownBy(() -> client.confirmHold("hold-6c0c2f41", "user-42", PACIENTE)).isInstanceOf(
            CatedraOutcomeUnknownException.class
        );
        assertThat(catedra.requests()).hasSize(1);
    }

    @Test
    void unaRedireccionNoSeSigueYEsInvalida() throws IOException {
        try (FakeHttpServer otroHost = FakeHttpServer.start()) {
            catedra.redirectTo(otroHost.baseUrl() + "/api/appointment-holds");

            assertThatThrownBy(() -> client.createHold(TURNO, "user-42")).isInstanceOf(CatedraInvalidResponseException.class);
            assertThat(otroHost.requests()).isEmpty();
        }
    }

    @Test
    void losMensajesNoTienenElTokenNiLaUrlNiElPaciente() {
        catedra.readBodyThenHang();

        assertThatThrownBy(() -> client.confirmHold("hold-6c0c2f41", "user-42", PACIENTE)).satisfies(e ->
            assertThat(e.getMessage()).doesNotContain(TOKEN, catedra.baseUrl().toString(), "Juan", "Perez")
        );
    }
}
