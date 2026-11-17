package ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catedra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraCancellation;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraAccessDeniedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraErrorCode;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraOutcomeUnknownException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraRejectedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraUnavailableException;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.client.FakeHttpServer;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * {@code POST /api/appointments/{reservationProcessId}/cancel} contra una cátedra simulada (contrato §12 y §13.1). Es una
 * operación con efectos: solo no haber podido conectar prueba que no se aplicó (ADR 0004).
 */
@ExtendWith(OutputCaptureExtension.class)
class CatedraAppointmentClientCancelTest {

    private static final String PROCESO = "process-5dfeb249";
    private static final String MOTIVO = "Me surgió un viaje imprevisto";
    private static final String OK =
        "{\"reservationId\":5857,\"reservationProcessId\":\"process-5dfeb249\",\"status\":\"CANCELLED\",\"cancelledAt\":\"2026-11-17T15:10:00Z\"}";

    private FakeHttpServer catedra;
    private CatedraApiProperties properties;

    @BeforeEach
    void startCatedra() throws IOException {
        catedra = FakeHttpServer.start();
        properties = new CatedraApiProperties();
        properties.setUrl(catedra.baseUrl());
        properties.setToken(CatedraOccupancyClientTest.TOKEN);
        properties.setReadTimeout(Duration.ofMillis(500));
    }

    @AfterEach
    void stopCatedra() {
        catedra.close();
    }

    private CatedraCancellation cancelar(String motivo) {
        return new CatedraAppointmentClient(new CatedraClientConfiguration().catedraRestClient(properties), properties).cancel(PROCESO, motivo);
    }

    @Test
    void cu10_6_un200ConCancelledDevuelveElInstanteDeLaCatedraYMandaElMotivo() {
        catedra.respondJson(200, OK);

        CatedraCancellation cancelacion = cancelar(MOTIVO);

        assertThat(cancelacion).isEqualTo(new CatedraCancellation(PROCESO, 5857L, Instant.parse("2026-11-17T15:10:00Z")));
        FakeHttpServer.ReceivedRequest pedido = catedra.onlyRequest();
        assertThat(pedido.method()).isEqualTo("POST");
        assertThat(pedido.path()).isEqualTo("/api/appointments/process-5dfeb249/cancel");
        assertThat(pedido.contentType()).isEqualTo("application/json");
        assertThat(pedido.body()).isEqualTo("{\"reason\":\"" + MOTIVO + "\"}");
        assertThat(pedido.authorization()).isEqualTo("Bearer " + CatedraOccupancyClientTest.TOKEN);
    }

    @Test
    void sinMotivoNoSeMandaCuerpo() {
        // Medido el 16/11: la cátedra acepta la cancelación sin cuerpo ni Content-Type.
        catedra.respondJson(200, OK);

        cancelar(null);

        FakeHttpServer.ReceivedRequest pedido = catedra.onlyRequest();
        assertThat(pedido.body()).isEmpty();
        assertThat(pedido.contentType()).isNull();
    }

    @Test
    void unIdRaroNoCambiaLaRuta() {
        catedra.respondJson(200, OK.replace(PROCESO, "a/b?c"));

        new CatedraAppointmentClient(new CatedraClientConfiguration().catedraRestClient(properties), properties).cancel("a/b?c", null);

        assertThat(catedra.onlyRequest().path()).isEqualTo("/api/appointments/a%2Fb%3Fc/cancel");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 5a: no se aplicó, o no se sabe
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void cu10_5a_sinPoderConectarSeSabeQueNoSeCancelo() throws IOException {
        properties.setUrl(FakeHttpServer.closedPortUrl());

        assertThatThrownBy(() -> cancelar(MOTIVO)).isInstanceOf(CatedraUnavailableException.class);
    }

    @Test
    void cu10_5a_unTimeoutDespuesDeConectarEsResultadoDesconocido() {
        catedra.readBodyThenHang();

        assertThatThrownBy(() -> cancelar(MOTIVO)).isInstanceOf(CatedraOutcomeUnknownException.class);
    }

    @Test
    void cu10_5a_unCorteSinRespuestaEsResultadoDesconocidoYElPostNoSeRepite() {
        catedra.closeWithoutResponding();

        assertThatThrownBy(() -> cancelar(MOTIVO)).isInstanceOf(CatedraOutcomeUnknownException.class);
        // A diferencia de un GET, el cliente del JDK no repite un POST.
        assertThat(catedra.requests()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(ints = { 500, 502, 503, 408, 429 })
    void cu10_5a_un5xxEsResultadoDesconocido(int status) {
        catedra.respondWithoutBody(status);

        assertThatThrownBy(() -> cancelar(MOTIVO)).isInstanceOf(CatedraOutcomeUnknownException.class);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "{\"reservationId\":5857,\"reservationProcessId\":\"process-5dfeb249\",\"status\":\"CONFIRMED\",\"cancelledAt\":\"2026-11-17T15:10:00Z\"}",
            "{\"reservationId\":5857,\"reservationProcessId\":\"process-otro\",\"status\":\"CANCELLED\",\"cancelledAt\":\"2026-11-17T15:10:00Z\"}",
            "{\"reservationId\":5857,\"reservationProcessId\":\"process-5dfeb249\",\"status\":\"CANCELLED\",\"cancelledAt\":\"ayer\"}",
            "{\"reservationId\":5857,\"reservationProcessId\":\"process-5dfeb249\",\"status\":\"CANCELLED\"}",
            "no es json",
            "null",
        }
    )
    void un200QueNoRespetaElContratoEsResultadoDesconocidoYNoRespuestaInvalida(String cuerpo) {
        // La cátedra dijo "éxito": la cancelación pudo haberse aplicado (ADR 0004).
        catedra.respondJson(200, cuerpo);

        assertThatThrownBy(() -> cancelar(MOTIVO)).isInstanceOf(CatedraOutcomeUnknownException.class);
    }

    @Test
    void unCuerpoCortadoAMitadEsResultadoDesconocido() {
        catedra.cutInTheMiddleOfTheBody(200, OK);

        assertThatThrownBy(() -> cancelar(MOTIVO)).isInstanceOf(CatedraOutcomeUnknownException.class);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 6a y 6b: los code de §13.1 para la cancelación
    // ---------------------------------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({ "409, RESERVATION_NOT_CANCELLABLE", "404, RESERVATION_NOT_FOUND", "403, RESERVATION_OWNER_MISMATCH", "400, VALIDATION_ERROR" })
    void cadaRechazoDeLaCancelacionLlegaConSuCode(int status, CatedraErrorCode code) {
        catedra.respondProblem(status, code.name());

        assertThatThrownBy(() -> cancelar(MOTIVO))
            .isInstanceOfSatisfying(CatedraRejectedException.class, rechazo -> {
                assertThat(rechazo.status()).isEqualTo(status);
                assertThat(rechazo.code()).isEqualTo(code);
            })
            .hasMessageNotContaining("detalle libre");
    }

    @Test
    void sinIntegracionTecnicaOSinCredencialEsAccesoDenegado() {
        catedra.respondProblem(403, "STUDENT_INTEGRATION_NOT_FOUND");
        assertThatThrownBy(() -> cancelar(MOTIVO)).isInstanceOf(CatedraAccessDeniedException.class);

        catedra.respondWithoutBody(401);
        assertThatThrownBy(() -> cancelar(MOTIVO)).isInstanceOf(CatedraAccessDeniedException.class);
    }

    @Test
    void elMotivoNoApareceEnElLogNiEnLosMensajes(CapturedOutput salida) {
        catedra.respondJson(200, "no es json");
        assertThatThrownBy(() -> cancelar(MOTIVO)).hasMessageNotContaining(MOTIVO);
        catedra.readBodyThenHang();
        assertThatThrownBy(() -> cancelar(MOTIVO)).hasMessageNotContaining(MOTIVO);

        assertThat(salida.getAll()).doesNotContain(MOTIVO);
    }
}
