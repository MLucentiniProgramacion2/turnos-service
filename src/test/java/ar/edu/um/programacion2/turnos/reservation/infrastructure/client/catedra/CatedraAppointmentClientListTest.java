package ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catedra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraReservation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraReservationList;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraReservationStatus;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraAccessDeniedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraErrorCode;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraInvalidResponseException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraRejectedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraUnavailableException;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.client.FakeHttpServer;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * {@code GET /api/appointments} contra una cátedra simulada (contrato §11 y §13.1): la paginación con
 * {@code X-Total-Count}, el {@code Link} que no se sigue, las cotas y los ítems que no se pueden leer.
 */
@ExtendWith(OutputCaptureExtension.class)
class CatedraAppointmentClientListTest {

    private static final LocalDate DESDE = LocalDate.parse("2027-01-05");
    private static final LocalDate HASTA = LocalDate.parse("2027-01-07");
    private static final String TELEFONO = "5492615551234";

    private FakeHttpServer catedra;
    private CatedraApiProperties properties;
    /** El cronómetro del cliente: cada pedido lo adelanta lo que "tardó", sin esperas reales. */
    private final AtomicLong reloj = new AtomicLong();
    private volatile long tardaCadaPedido;

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

    private CatedraAppointmentClient client() {
        return new CatedraAppointmentClient(new CatedraClientConfiguration().catedraRestClient(properties), properties, () ->
            reloj.get()
        );
    }

    private CatedraReservationList listar() {
        return client().listReservations("user-1101", DESDE, HASTA);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Una página
    // ---------------------------------------------------------------------------------------------------------------

    /** Las dos reservas reales de la cuenta técnica (16/11), con el formato del contrato §11. */
    @Test
    void unaPaginaConLasReservasRealesDeLaCuentaTecnica() {
        catedra.respond(200, "application/json", "[" + item(5858, "process-44886a21", "12:30:00", "FAILED", null) + "," + item(5857, "process-5dfeb249", "11:30:00", "CONFIRMED", "\"2026-11-15T20:46:25.859774Z\"") + "]", Map.of("X-Total-Count", "2"));

        CatedraReservationList lista = listar();

        assertThat(lista.ignoredItems()).isZero();
        assertThat(lista.items()).extracting(CatedraReservation::reservationProcessId).containsExactly("process-44886a21", "process-5dfeb249");
        CatedraReservation confirmada = lista.find("process-5dfeb249").orElseThrow();
        assertThat(confirmada.status()).isEqualTo(CatedraReservationStatus.CONFIRMED);
        assertThat(confirmada.reservationId()).isEqualTo(5857);
        assertThat(confirmada.date()).isEqualTo(LocalDate.parse("2027-01-05"));
        assertThat(confirmada.startTime()).isEqualTo(LocalTime.of(11, 30));
        // confirmedAt con microsegundos, como la cátedra real.
        assertThat(confirmada.confirmedAt()).isEqualTo(Instant.parse("2026-11-15T20:46:25.859774Z"));
        assertThat(confirmada.externalPatientId()).isEqualTo("user-1101");
        // La fallida tiene reservationId: no quiere decir que se confirmó.
        assertThat(lista.find("process-44886a21").orElseThrow().status()).isEqualTo(CatedraReservationStatus.FAILED);

        FakeHttpServer.ReceivedRequest pedido = catedra.onlyRequest();
        assertThat(pedido.method()).isEqualTo("GET");
        assertThat(pedido.path()).isEqualTo("/api/appointments");
        assertThat(pedido.query()).isEqualTo("externalPatientId=user-1101&from=2027-01-05&to=2027-01-07&page=0&size=200");
        assertThat(pedido.authorization()).isEqualTo("Bearer " + CatedraOccupancyClientTest.TOKEN);
    }

    @Test
    void elTelefonoYLosNombresDelPacienteNoLleganAlModelo() {
        // Ni siquiera hay dónde guardarlos: el registro del dominio no tiene esos componentes.
        List<String> componentes = Arrays.stream(CatedraReservation.class.getRecordComponents()).map(RecordComponent::getName).toList();
        assertThat(componentes).noneMatch(nombre -> nombre.toLowerCase().contains("patient") && !nombre.equals("externalPatientId"));
        List<String> delDto = Arrays.stream(CatedraDtos.AppointmentItem.class.getRecordComponents()).map(RecordComponent::getName).toList();
        assertThat(delDto).doesNotContain("patientPhone", "patientFirstName", "patientLastName");

        catedra.respond(200, "application/json", "[" + item(5857, "process-5dfeb249", "11:30:00", "CONFIRMED", null) + "]", Map.of("X-Total-Count", "1"));

        assertThat(listar().items().getFirst().toString()).doesNotContain(TELEFONO, "Juan", "Perez");
    }

    @Test
    void sinReservasEsUnaListaVaciaConUnSoloPedido() {
        catedra.respond(200, "application/json", "[]", Map.of("X-Total-Count", "0"));

        assertThat(listar().items()).isEmpty();
        assertThat(catedra.requests()).hasSize(1);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Varias páginas
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void recorreTodasLasPaginasYLaUltimaIncompleta() {
        properties.setListPageSize(2);
        paginas(5, 2);

        CatedraReservationList lista = listar();

        assertThat(lista.items()).extracting(CatedraReservation::reservationProcessId).containsExactly("process-1", "process-2", "process-3", "process-4", "process-5");
        assertThat(paginasPedidas()).containsExactly(0, 1, 2);
    }

    @Test
    void elFinSaleDeXTotalCountAunqueLaCatedraRecorteElTamanoDePagina() {
        // Se piden 3 por página y la cátedra devuelve 2 (como cuando recorta a 2000 sin avisar): una página de 2 no es la
        // última mientras falten reservas para llegar al total.
        properties.setListPageSize(3);
        paginas(5, 2);

        assertThat(listar().items()).hasSize(5);
        assertThat(paginasPedidas()).containsExactly(0, 1, 2);
    }

    @Test
    void elLinkConOtroHostNoSeSigue() {
        properties.setListPageSize(2);
        catedra.respondByQuery(query -> {
            int pagina = pagina(query);
            List<String> items = new ArrayList<>();
            for (int n = pagina * 2 + 1; n <= Math.min(pagina * 2 + 2, 3); n++) {
                items.add(item(n, "process-" + n, "09:00:00", "CONFIRMED", null));
            }
            String link = "<http://otro-host.invalid:9999/api/appointments?page=7&size=2>; rel=\"next\"";
            return new FakeHttpServer.Reply(200, "[" + String.join(",", items) + "]", Map.of("X-Total-Count", "3", "Link", link));
        });

        assertThat(listar().items()).hasSize(3);
        // La página 7 del Link no se pidió nunca: la siguiente se calcula con page + 1.
        assertThat(paginasPedidas()).containsExactly(0, 1);
    }

    @Test
    void unItemRepetidoEntrePaginasCuentaUnaVez() {
        properties.setListPageSize(2);
        catedra.respondByQuery(query ->
            pagina(query) == 0
                ? new FakeHttpServer.Reply(200, "[" + item(1, "process-1", "10:00:00", "CONFIRMED", null) + "," + item(2, "process-2", "09:30:00", "CONFIRMED", null) + "]", Map.of("X-Total-Count", "4"))
                : new FakeHttpServer.Reply(200, "[" + item(2, "process-2", "09:30:00", "CONFIRMED", null) + "," + item(3, "process-3", "09:00:00", "CONFIRMED", null) + "]", Map.of("X-Total-Count", "4"))
        );

        assertThat(listar().items()).extracting(CatedraReservation::reservationProcessId).containsExactly("process-1", "process-2", "process-3");
    }

    @Test
    void siElTotalCambiaEntrePaginasLaListaNoEsConsistenteYEsNoDisponible() {
        properties.setListPageSize(2);
        catedra.respondByQuery(query ->
            new FakeHttpServer.Reply(200, "[" + item(1, "process-" + pagina(query) + "a", "10:00:00", "CONFIRMED", null) + "," + item(2, "process-" + pagina(query) + "b", "09:00:00", "CONFIRMED", null) + "]", Map.of("X-Total-Count", pagina(query) == 0 ? "3" : "4"))
        );

        assertThatThrownBy(this::listar).isInstanceOf(CatedraUnavailableException.class);
    }

    @Test
    void masPaginasQueElTopeEsRespuestaInvalidaYNoSePideOtra() {
        properties.setListPageSize(1);
        properties.setListMaxPages(3);
        paginas(10, 1);

        assertThatThrownBy(this::listar).isInstanceOf(CatedraInvalidResponseException.class).hasMessageContaining("3 páginas");
        assertThat(paginasPedidas()).containsExactly(0, 1, 2);
    }

    @Test
    void unaPaginaVaciaAntesDeLlegarAlTotalEsRespuestaInvalida() {
        catedra.respond(200, "application/json", "[]", Map.of("X-Total-Count", "3"));

        assertThatThrownBy(this::listar).isInstanceOf(CatedraInvalidResponseException.class);
        assertThat(catedra.requests()).hasSize(1);
    }

    @Test
    void unaPaginaConMasItemsQueElTamanoEsRespuestaInvalida() {
        properties.setListPageSize(1);
        paginas(3, 2);

        assertThatThrownBy(this::listar).isInstanceOf(CatedraInvalidResponseException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "dos", "-1" })
    void sinUnXTotalCountValidoEsRespuestaInvalida(String total) {
        catedra.respond(200, "application/json", "[]", total.isEmpty() ? Map.of() : Map.of("X-Total-Count", total));

        assertThatThrownBy(this::listar).isInstanceOf(CatedraInvalidResponseException.class);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // El plazo total
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void noSeEmpiezaUnaPaginaQueNoPuedeTerminarDentroDelPlazo() {
        // Plazo 10 s y peor caso de un pedido 2 s + 5 s = 7 s: si la primera tardó 4 s, quedan 6 y no se pide la segunda.
        properties.setReadTimeout(Duration.ofSeconds(5));
        properties.setListPageSize(1);
        tardaCadaPedido = Duration.ofSeconds(4).toNanos();
        paginas(2, 1);

        assertThatThrownBy(this::listar).isInstanceOf(CatedraUnavailableException.class).hasMessageContaining("plazo");
        assertThat(paginasPedidas()).containsExactly(0);
    }

    @Test
    void conPedidosRapidosElPlazoAlcanzaParaVariasPaginas() {
        properties.setReadTimeout(Duration.ofSeconds(5));
        properties.setListPageSize(1);
        tardaCadaPedido = Duration.ofMillis(900).toNanos();
        paginas(4, 1);

        assertThat(listar().items()).hasSize(4);
        // Antes de la cuarta, pasaron 2,7 s y quedan 7,3: alcanza para el peor caso de un pedido.
        assertThat(paginasPedidas()).containsExactly(0, 1, 2, 3);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Ítems que no se pueden leer
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void unStatusDesconocidoSeConservaYUnItemSinIdOConFechaIlegibleSeIgnoraYSeCuenta() {
        catedra.respond(
            200,
            "application/json",
            "[" +
            item(1, "process-1", "10:00:00", "ON_HOLD", null) +
            "," +
            item(2, null, "09:30:00", "CONFIRMED", null) +
            "," +
            item(3, "process-3", "9 y media", "CONFIRMED", null) +
            "," +
            item(4, "process-4", "09:00", "CANCELLED", "\"ayer\"") +
            "]",
            Map.of("X-Total-Count", "4")
        );

        CatedraReservationList lista = listar();

        assertThat(lista.ignoredItems()).isEqualTo(2);
        assertThat(lista.provesAbsence()).isFalse();
        assertThat(lista.find("process-1").orElseThrow().status()).isEqualTo(CatedraReservationStatus.UNRECOGNIZED);
        // Hora HH:mm e instante ilegible (que queda ausente).
        CatedraReservation cancelada = lista.find("process-4").orElseThrow();
        assertThat(cancelada.startTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(cancelada.confirmedAt()).isNull();
    }

    @Test
    void unReservationIdQueNoEsUnNumeroHaceIlegibleLaPagina() {
        catedra.respond(200, "application/json", "[{\"reservationId\":\"ocho\",\"reservationProcessId\":\"p\"}]", Map.of("X-Total-Count", "1"));
        assertThatThrownBy(this::listar).isInstanceOf(CatedraInvalidResponseException.class);

        catedra.respond(200, "application/json", "{\"no\":\"es una lista\"}", Map.of("X-Total-Count", "1"));
        assertThatThrownBy(this::listar).isInstanceOf(CatedraInvalidResponseException.class);
    }

    @Test
    void elCuerpoNoVaAlLogNiCuandoUnItemSeIgnoraNiCuandoLaPaginaEsIlegible(CapturedOutput salida) {
        catedra.respond(200, "application/json", "[" + item(2, null, "09:30:00", "CONFIRMED", null) + "]", Map.of("X-Total-Count", "1"));
        listar();
        catedra.respond(200, "application/json", "[" + item(2, "p", "09:30:00", "CONFIRMED", null).replace("\"reservationId\":2", "\"reservationId\":2.5") + "]", Map.of("X-Total-Count", "1"));
        assertThatThrownBy(this::listar).isInstanceOf(CatedraInvalidResponseException.class);

        assertThat(salida.getAll()).doesNotContain(TELEFONO).doesNotContain("Juan").doesNotContain("motivo del paciente");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Fallas (todas terminan en CU-09 4b/4c)
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void un400SinCodeEsRespuestaInvalidaComoDiceElAdr0004() {
        // Medido el 16/11: status=NO_EXISTE responde 400 con "code": null.
        catedra.respond(400, "application/problem+json", "{\"title\":\"Bad Request\",\"status\":400,\"code\":null}", Map.of());

        assertThatThrownBy(this::listar).isInstanceOf(CatedraInvalidResponseException.class);
    }

    @Test
    void unRangoInvalidoEsUnRechazoConSuCode() {
        catedra.respondProblem(400, "INVALID_DATE_RANGE");

        assertThatThrownBy(this::listar)
            .isInstanceOfSatisfying(CatedraRejectedException.class, rechazo -> assertThat(rechazo.code()).isEqualTo(CatedraErrorCode.INVALID_DATE_RANGE))
            .hasMessageNotContaining("detalle libre");
    }

    @Test
    void sinIntegracionTecnicaOSinCredencialEsAccesoDenegado() {
        catedra.respondProblem(403, "STUDENT_INTEGRATION_NOT_FOUND");
        assertThatThrownBy(this::listar).isInstanceOf(CatedraAccessDeniedException.class);

        catedra.respondWithoutBody(401);
        assertThatThrownBy(this::listar).isInstanceOf(CatedraAccessDeniedException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = { 500, 502, 503, 429 })
    void unaFallaTransitoriaEsNoDisponibleNuncaResultadoDesconocido(int status) {
        catedra.respondWithoutBody(status);

        assertThatThrownBy(this::listar).isInstanceOf(CatedraUnavailableException.class);
    }

    @Test
    void unaCatedraQueNoRespondeATiempoOApagadaEsNoDisponible() throws IOException {
        catedra.readBodyThenHang();
        assertThatThrownBy(this::listar).isInstanceOf(CatedraUnavailableException.class);

        properties.setUrl(FakeHttpServer.closedPortUrl());
        assertThatThrownBy(this::listar).isInstanceOf(CatedraUnavailableException.class);
    }

    @Test
    void siElServidorCierraSinResponderElJdkRepiteElGetUnaVez() {
        // Medido en el #10 (ADR 0004): con un GET es inofensivo.
        catedra.closeWithoutResponding();

        assertThatThrownBy(this::listar).isInstanceOf(CatedraUnavailableException.class);
        assertThat(catedra.requests()).hasSize(2);
    }

    /**
     * Medido para el apagado de CU-11: al interrumpir el hilo, un pedido en vuelo <strong>no espera su timeout</strong>. El
     * cliente del JDK corta la espera, la falla llega como "no disponible" y el hilo conserva la marca de interrupción.
     */
    @Test
    void unPedidoEnVueloSeCortaAlInterrumpirElHilo() throws Exception {
        properties.setReadTimeout(Duration.ofSeconds(5));
        java.util.concurrent.CountDownLatch llego = new java.util.concurrent.CountDownLatch(1);
        catedra.beforeResponding("/api/appointments", llego::countDown);
        catedra.readBodyThenHang();
        AtomicReference<Throwable> falla = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean siguioInterrumpido = new java.util.concurrent.atomic.AtomicBoolean();
        Thread hilo = new Thread(() -> {
            try {
                listar();
            } catch (RuntimeException e) {
                falla.set(e);
                siguioInterrumpido.set(Thread.currentThread().isInterrupted());
            }
        });
        hilo.start();
        assertThat(llego.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        long antes = System.nanoTime();
        hilo.interrupt();
        hilo.join(10_000);
        long tardo = Duration.ofNanos(System.nanoTime() - antes).toMillis();

        assertThat(hilo.isAlive()).isFalse();
        assertThat(tardo).as("ms desde la interrupción").isLessThan(1000);
        assertThat(falla.get()).isInstanceOf(CatedraUnavailableException.class);
        // Y el hilo sigue marcado como interrumpido: la próxima espera del backoff de CU-11 corta la ejecución.
        assertThat(siguioInterrumpido.get()).isTrue();
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** Una cátedra con {@code total} reservas en páginas de {@code devuelve} ítems, que adelanta el cronómetro en cada pedido. */
    private void paginas(int total, int devuelve) {
        catedra.respondByQuery(query -> {
            reloj.addAndGet(tardaCadaPedido);
            int pagina = pagina(query);
            List<String> items = new ArrayList<>();
            for (int n = pagina * devuelve + 1; n <= Math.min(pagina * devuelve + devuelve, total); n++) {
                items.add(item(n, "process-" + n, "09:00:00", "CONFIRMED", null));
            }
            return new FakeHttpServer.Reply(200, "[" + String.join(",", items) + "]", Map.of("X-Total-Count", String.valueOf(total)));
        });
    }

    private List<Integer> paginasPedidas() {
        return catedra.requests().stream().map(pedido -> pagina(pedido.query())).toList();
    }

    private static int pagina(String query) {
        Matcher matcher = Pattern.compile("(?:^|&)page=(\\d+)").matcher(query);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    /** Un ítem con el formato del contrato §11, con los datos personales que turnos tiene que descartar. */
    private static String item(long reservationId, String reservationProcessId, String startTime, String status, String confirmedAt) {
        return """
            {"reservationId":%d,"reservationProcessId":%s,"externalPatientId":"user-1101","appointmentDate":"2027-01-05",
             "startTime":"%s","endTime":"12:00:00","professionalId":202,"professionalFirstName":"Martin","professionalLastName":"Rivas",
             "patientFirstName":"Juan","patientLastName":"Perez","patientPhone":"+%s","status":"%s","createdAt":"2026-11-15T20:40:00Z",
             "confirmedAt":%s,"cancelledAt":null,"cancellationReason":"motivo del paciente"}""".formatted(
                reservationId,
                reservationProcessId == null ? "null" : "\"" + reservationProcessId + "\"",
                startTime,
                TELEFONO,
                status,
                confirmedAt
            );
    }
}
