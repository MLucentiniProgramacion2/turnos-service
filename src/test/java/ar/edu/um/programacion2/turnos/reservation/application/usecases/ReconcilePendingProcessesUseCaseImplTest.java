package ar.edu.um.programacion2.turnos.reservation.application.usecases;

import static ar.edu.um.programacion2.turnos.reservation.application.usecases.ReservationProcesses.ANTES;
import static ar.edu.um.programacion2.turnos.reservation.application.usecases.ReservationProcesses.VENCE;
import static ar.edu.um.programacion2.turnos.reservation.application.usecases.ReservationProcesses.id;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.CANCELLED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.CONFIRMED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.CREATING_HOLD;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.EXPIRED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.FAILED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.HOLD_CREATED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.PHONE_REJECTED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.PHONE_REQUESTED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.PHONE_SUBMITTED;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.WAITING_FOR_PHONE_REQUEST;
import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.turnos.reservation.domain.model.AppointmentSlot;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraCancellation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraHold;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraReservation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraReservationList;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraReservationStatus;
import ar.edu.um.programacion2.turnos.reservation.domain.model.Closing;
import ar.edu.um.programacion2.turnos.reservation.domain.model.HoldConfirmation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PatientName;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReconciliationPolicy;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReconciliationReport;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReconciliationReport.Ending;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.AppointmentGateway;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraAccessDeniedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraErrorCode;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraOutcomeUnknownException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraRejectedException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraUnavailableException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.HoldGateway;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * CU-11 con los puertos simulados: un test por flujo alternativo, nombrado con su número. Los procesos salen de
 * {@link ReservationProcesses}: el último cambio de estado es {@code ANTES} (12:20; {@code CREATING_HOLD}, 12:19) y el hold
 * vence en {@code VENCE} (12:40). Cada proceso es de otro usuario: hay a lo sumo un intermedio por usuario (DPT-17).
 */
@ExtendWith(OutputCaptureExtension.class)
class ReconcilePendingProcessesUseCaseImplTest {

    /** Más de 1 minuto después del último cambio y antes del vencimiento. */
    private static final Instant TEMPRANO = ANTES.plus(Duration.ofMinutes(2));
    /** Pasado {@code expiresAt} + 1 minuto. */
    private static final Instant VENCIDO = VENCE.plus(Duration.ofMinutes(1)).plusSeconds(1);

    private final FakeTransactions transacciones = new FakeTransactions();
    private final InMemoryReservationProcessRepository procesos = new InMemoryReservationProcessRepository(transacciones);
    private final Reloj reloj = new Reloj(TEMPRANO);
    private final Catedra catedra = new Catedra();
    private final List<Duration> esperas = new ArrayList<>();
    private ReconciliationLimits limites = new ReconciliationLimits(50, Duration.ofMinutes(2));
    private RetryBackoff.Sleeper dormir = espera -> {
        esperas.add(espera);
        reloj.adelantar(espera);
    };

    @AfterEach
    void limpiarInterrupcion() {
        Thread.interrupted();
    }

    private ReconcilePendingProcessesUseCaseImpl casoDeUso() {
        return new ReconcilePendingProcessesUseCaseImpl(
            procesos,
            new ReadTransactions(transacciones),
            catedra,
            catedra,
            new ReservationProcessUpdater(procesos, transacciones),
            new ReconciliationPolicy(Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(10)),
            limites,
            new RetryBackoff(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4)), espera -> dormir.sleep(espera)),
            reloj
        );
    }

    /** Un reloj que el test adelanta. */
    private static final class Reloj extends Clock {

        private final AtomicReference<Instant> ahora;

        Reloj(Instant inicio) {
            this.ahora = new AtomicReference<>(inicio);
        }

        void en(Instant instante) {
            ahora.set(instante);
        }

        void adelantar(Duration cuanto) {
            ahora.updateAndGet(actual -> actual.plus(cuanto));
        }

        @Override
        public Instant instant() {
            return ahora.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    /** La cátedra simulada: listado y confirm. Anota cada pedido y comprueba que ninguno se haga adentro de una transacción. */
    private final class Catedra implements AppointmentGateway, HoldGateway {

        record Confirm(String holdId, String externalPatientId, PatientName paciente) {}

        final List<String> listados = new ArrayList<>();
        final List<Confirm> confirms = new ArrayList<>();
        final List<CatedraReservation> reservas = new ArrayList<>();
        int ignorados;
        final Deque<CatedraException> fallasDelListado = new ArrayDeque<>();
        final Deque<CatedraException> fallasDelConfirm = new ArrayDeque<>();
        Runnable alListar = () -> {};
        Duration tardaCadaListado = Duration.ZERO;

        @Override
        public CatedraReservationList listReservations(String externalPatientId, LocalDate from, LocalDate to) {
            assertThat(transacciones.isActive()).as("ningún pedido a la cátedra adentro de una transacción").isFalse();
            listados.add(externalPatientId + " " + from + ".." + to);
            reloj.adelantar(tardaCadaListado);
            alListar.run();
            if (!fallasDelListado.isEmpty()) {
                throw fallasDelListado.poll();
            }
            return new CatedraReservationList(reservas.stream().filter(r -> r.externalPatientId().equals(externalPatientId)).toList(), ignorados);
        }

        @Override
        public CatedraCancellation cancel(String reservationProcessId, String reason) {
            throw new AssertionError("CU-11 nunca cancela");
        }

        @Override
        public CatedraHold createHold(AppointmentSlot slot, String externalPatientId) {
            throw new AssertionError("CU-11 nunca crea un hold (DPT-06)");
        }

        @Override
        public HoldConfirmation confirmHold(String holdId, String externalPatientId, PatientName patient) {
            assertThat(transacciones.isActive()).as("ningún pedido a la cátedra adentro de una transacción").isFalse();
            confirms.add(new Confirm(holdId, externalPatientId, patient));
            if (!fallasDelConfirm.isEmpty()) {
                throw fallasDelConfirm.poll();
            }
            return new HoldConfirmation("process-1", VENCE.plusSeconds(5));
        }

        void tiene(int n, long userId, CatedraReservationStatus estado) {
            reservas.add(
                new CatedraReservation(
                    "process-" + n,
                    "user-" + userId,
                    8251L,
                    LocalDate.parse("2026-12-15"),
                    LocalTime.of(10, 0),
                    estado,
                    estado == CatedraReservationStatus.CONFIRMED || estado == CatedraReservationStatus.CANCELLED ? ANTES : null,
                    estado == CatedraReservationStatus.CANCELLED ? ANTES.plusSeconds(30) : null,
                    null
                )
            );
        }
    }

    private void proceso(ReservationProcessStatus estado, int n) {
        procesos.put(ReservationProcesses.in(estado, 100 + n, LocalTime.of(10, 0), n));
    }

    private ReservationProcessStatus estado(int n) {
        return procesos.get(id(n)).getStatus();
    }

    private String cierre(int n) {
        return procesos.get(id(n)).getClosing().map(Closing::reason).orElse(null);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Flujo principal y la tabla
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void cu11_2_soloRevisaLosIntermediosYSoloLosQueYaCorresponden() {
        proceso(CONFIRMED, 1);
        proceso(CANCELLED, 2);
        proceso(PHONE_REQUESTED, 3);
        reloj.en(ANTES.plusSeconds(60));
        proceso(HOLD_CREATED, 4);

        ReconciliationReport reporte = casoDeUso().reconcile();

        // A las 12:21 el HOLD_CREATED tiene exactamente 1 minuto (DPT-24) y el PHONE_REQUESTED no venció: nada que hacer.
        assertThat(reporte).isEqualTo(new ReconciliationReport.Completed(0, 0, 0, 0, Ending.FINISHED));
        assertThat(catedra.listados).isEmpty();
        assertThat(procesos.writes()).isEmpty();
    }

    @Test
    void cu11_creatingHoldConMasDe1MinutoPasaAFailedSinPreguntarleALaCatedra() {
        proceso(CREATING_HOLD, 1);

        ReconciliationReport reporte = casoDeUso().reconcile();

        assertThat(estado(1)).isEqualTo(FAILED);
        assertThat(cierre(1)).isEqualTo("HOLD_OUTCOME_UNKNOWN");
        assertThat(catedra.listados).isEmpty();
        assertThat(catedra.confirms).isEmpty();
        assertThat(reporte).isEqualTo(new ReconciliationReport.Completed(1, 1, 0, 0, Ending.FINISHED));
    }

    @Test
    void cu11_3a_phonePendingEnLaCatedraEsQueElConfirmSeAplico() {
        proceso(HOLD_CREATED, 1);
        catedra.tiene(1, 101, CatedraReservationStatus.PHONE_PENDING);

        casoDeUso().reconcile();

        assertThat(estado(1)).isEqualTo(WAITING_FOR_PHONE_REQUEST);
        assertThat(catedra.listados).containsExactly("user-101 2026-12-15..2026-12-15");
        assertThat(catedra.confirms).isEmpty();
    }

    @Test
    void cu11_3a_siLaCatedraNoLoTieneSeReintentaElConfirmConLosDatosGuardados() {
        proceso(HOLD_CREATED, 1);

        casoDeUso().reconcile();

        assertThat(catedra.confirms).containsExactly(new Catedra.Confirm("hold-1", "user-101", new PatientName("Ana", "Pérez")));
        assertThat(estado(1)).isEqualTo(WAITING_FOR_PHONE_REQUEST);
        assertThat(procesos.get(id(1)).getHold().orElseThrow().expiresAt()).isEqualTo(VENCE.plusSeconds(5));
    }

    @Test
    void cu11_3a_alreadyConfirmedEsUnExito() {
        proceso(HOLD_CREATED, 1);
        catedra.fallasDelConfirm.add(new CatedraRejectedException(409, CatedraErrorCode.RESERVATION_PROCESS_ALREADY_CONFIRMED, "409"));

        casoDeUso().reconcile();

        assertThat(estado(1)).isEqualTo(WAITING_FOR_PHONE_REQUEST);
    }

    @Test
    void cu11_3a_holdExpiredPasaAExpired() {
        proceso(HOLD_CREATED, 1);
        catedra.fallasDelConfirm.add(new CatedraRejectedException(404, CatedraErrorCode.HOLD_EXPIRED, "404"));

        casoDeUso().reconcile();

        assertThat(estado(1)).isEqualTo(EXPIRED);
        assertThat(cierre(1)).isEqualTo("HOLD_EXPIRED");
    }

    @Test
    void cu11_3a_otroRechazoFuncionalPasaAFailedConElCode() {
        proceso(HOLD_CREATED, 1);
        catedra.fallasDelConfirm.add(new CatedraRejectedException(409, CatedraErrorCode.SLOT_ALREADY_RESERVED, "409"));

        casoDeUso().reconcile();

        assertThat(estado(1)).isEqualTo(FAILED);
        assertThat(cierre(1)).isEqualTo("SLOT_ALREADY_RESERVED");
    }

    @Test
    void cu11_3a_unResultadoDesconocidoDelConfirmNoSeRepiteYQuedaParaLaProxima() {
        proceso(HOLD_CREATED, 1);
        catedra.fallasDelConfirm.add(new CatedraOutcomeUnknownException("timeout"));

        ReconciliationReport reporte = casoDeUso().reconcile();

        assertThat(catedra.confirms).hasSize(1);
        assertThat(esperas).isEmpty();
        assertThat(estado(1)).isEqualTo(HOLD_CREATED);
        assertThat(reporte).isEqualTo(new ReconciliationReport.Completed(1, 0, 0, 1, Ending.FINISHED));
    }

    @Test
    void cu11_3a_unaListaConItemsIgnoradosNoPruebaQueElConfirmFalte() {
        proceso(HOLD_CREATED, 1);
        catedra.ignorados = 1;

        casoDeUso().reconcile();

        assertThat(catedra.confirms).isEmpty();
        assertThat(estado(1)).isEqualTo(HOLD_CREATED);
    }

    @Test
    void cu11_3a_ausenteDespuesDeExpiresAtMasElMargenPasaAExpiredSinReintentarElConfirm() {
        proceso(HOLD_CREATED, 1);
        reloj.en(VENCIDO);

        casoDeUso().reconcile();

        assertThat(catedra.confirms).isEmpty();
        assertThat(estado(1)).isEqualTo(EXPIRED);
        assertThat(cierre(1)).isEqualTo("NOT_FOUND_AFTER_EXPIRY");
    }

    @Test
    void cu11_3b_vencidoSegunLoQueInformaLaCatedra() {
        proceso(WAITING_FOR_PHONE_REQUEST, 1);
        proceso(PHONE_REQUESTED, 2);
        proceso(PHONE_SUBMITTED, 3);
        proceso(PHONE_REJECTED, 4);
        proceso(PHONE_REQUESTED, 5);
        catedra.tiene(1, 101, CatedraReservationStatus.CONFIRMED);
        catedra.tiene(2, 102, CatedraReservationStatus.CANCELLED);
        catedra.tiene(3, 103, CatedraReservationStatus.FAILED);
        catedra.tiene(5, 105, CatedraReservationStatus.PHONE_PENDING);
        reloj.en(VENCIDO);

        ReconciliationReport reporte = casoDeUso().reconcile();

        assertThat(estado(1)).isEqualTo(CONFIRMED);
        assertThat(estado(2)).isEqualTo(CANCELLED);
        assertThat(procesos.get(id(2)).getConfirmation()).isPresent();
        assertThat(estado(3)).isEqualTo(EXPIRED);
        assertThat(cierre(3)).isEqualTo("FAILED_IN_CATEDRA");
        assertThat(estado(4)).isEqualTo(EXPIRED);
        assertThat(cierre(4)).isEqualTo("NOT_FOUND_AFTER_EXPIRY");
        // PHONE_PENDING: se espera hasta expiresAt + 10 min.
        assertThat(estado(5)).isEqualTo(PHONE_REQUESTED);
        assertThat(reporte).isEqualTo(new ReconciliationReport.Completed(5, 4, 1, 0, Ending.FINISHED));
        assertThat(catedra.confirms).isEmpty();
    }

    @Test
    void cu11_3b_phonePendingPasadaLaEsperaMaximaPasaAExpiredConUnaAdvertencia(CapturedOutput salida) {
        proceso(PHONE_SUBMITTED, 1);
        catedra.tiene(1, 101, CatedraReservationStatus.PHONE_PENDING);
        reloj.en(VENCE.plus(Duration.ofMinutes(10)).plusSeconds(1));

        casoDeUso().reconcile();

        assertThat(estado(1)).isEqualTo(EXPIRED);
        assertThat(cierre(1)).isEqualTo("PHONE_PENDING_TIMEOUT");
        assertThat(salida.getOut()).contains(" WARN ").contains("PHONE_PENDING_TIMEOUT").contains("process-1");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Alternativos
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void cu11_1a_siYaHayUnaEjecucionEnCursoEstaSeSalteaSinEsperar() throws Exception {
        proceso(HOLD_CREATED, 1);
        CountDownLatch adentro = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        catedra.alListar = () -> {
            adentro.countDown();
            try {
                soltar.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        ReconcilePendingProcessesUseCaseImpl casoDeUso = casoDeUso();
        ExecutorService hilo = Executors.newSingleThreadExecutor();
        try {
            Future<ReconciliationReport> primera = hilo.submit(casoDeUso::reconcile);
            assertThat(adentro.await(10, TimeUnit.SECONDS)).isTrue();

            // La segunda, desde otro hilo (el del test), mientras la primera está adentro: no espera.
            assertThat(casoDeUso.reconcile()).isEqualTo(new ReconciliationReport.Skipped());

            soltar.countDown();
            assertThat(primera.get(10, TimeUnit.SECONDS)).isInstanceOf(ReconciliationReport.Completed.class);
        } finally {
            hilo.shutdownNow();
        }
        assertThat(catedra.listados).hasSize(1);
        // Terminada la primera, una nueva corre.
        assertThat(casoDeUso.reconcile()).isInstanceOf(ReconciliationReport.Completed.class);
    }

    @Test
    void cu11_4a_siLaCatedraNoRespondeSeReintentaCon1y2SegundosYDespuesSeAplica() {
        proceso(PHONE_REQUESTED, 1);
        catedra.tiene(1, 101, CatedraReservationStatus.CONFIRMED);
        catedra.fallasDelListado.add(new CatedraUnavailableException("timeout"));
        catedra.fallasDelListado.add(new CatedraUnavailableException("timeout"));
        reloj.en(VENCIDO);

        casoDeUso().reconcile();

        assertThat(esperas).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
        assertThat(catedra.listados).hasSize(3);
        assertThat(estado(1)).isEqualTo(CONFIRMED);
    }

    @Test
    void cu11_4a_4c_siSeAgotanLosReintentosSeCortaLaEjecucionYLosProcesosQuedanComoEstan() {
        proceso(PHONE_REQUESTED, 1);
        proceso(PHONE_SUBMITTED, 2);
        for (int i = 0; i < 4; i++) {
            catedra.fallasDelListado.add(new CatedraUnavailableException("timeout"));
        }
        reloj.en(VENCIDO);

        ReconciliationReport reporte = casoDeUso().reconcile();

        assertThat(esperas).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
        assertThat(catedra.listados).hasSize(4);
        assertThat(reporte).isEqualTo(new ReconciliationReport.Completed(1, 0, 0, 0, Ending.CATEDRA_UNAVAILABLE));
        assertThat(estado(1)).isEqualTo(PHONE_REQUESTED);
        assertThat(estado(2)).isEqualTo(PHONE_SUBMITTED);
    }

    @Test
    void cu11_4a_elConfirmSinConectarSeReintentaPeroNuncaDespuesDeExpiresAt() {
        proceso(HOLD_CREATED, 1);
        for (int i = 0; i < 4; i++) {
            catedra.fallasDelConfirm.add(new CatedraUnavailableException("no conectó"));
        }
        // Quedan 4,5 s para que venza: entran tres intentos (con esperas de 1 y 2 s); después de la espera de 4 s ya venció
        // y el cuarto no se manda.
        reloj.en(VENCE.minusMillis(4500));

        ReconciliationReport reporte = casoDeUso().reconcile();

        assertThat(catedra.confirms).hasSize(3);
        assertThat(esperas).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
        assertThat(estado(1)).isEqualTo(HOLD_CREATED);
        assertThat(reporte).isEqualTo(new ReconciliationReport.Completed(1, 0, 1, 0, Ending.FINISHED));
    }

    @Test
    void cu11_4b_laCredencialRechazadaCortaLaEjecucionSinReintentos(CapturedOutput salida) {
        proceso(PHONE_REQUESTED, 1);
        proceso(PHONE_SUBMITTED, 2);
        catedra.fallasDelListado.add(new CatedraAccessDeniedException("403 STUDENT_INTEGRATION_NOT_FOUND"));
        reloj.en(VENCIDO);

        ReconciliationReport reporte = casoDeUso().reconcile();

        assertThat(reporte).isEqualTo(new ReconciliationReport.Completed(1, 0, 0, 0, Ending.ACCESS_DENIED));
        assertThat(catedra.listados).hasSize(1);
        assertThat(esperas).isEmpty();
        assertThat(salida.getOut()).contains(" ERROR ").contains("credencial técnica");
    }

    @Test
    void unaListaRechazadaParaUnProcesoNoCortaLaEjecucion() {
        proceso(PHONE_REQUESTED, 1);
        proceso(PHONE_SUBMITTED, 2);
        catedra.fallasDelListado.add(new CatedraRejectedException(400, CatedraErrorCode.INVALID_DATE_RANGE, "400"));
        reloj.en(VENCIDO);

        ReconciliationReport reporte = casoDeUso().reconcile();

        assertThat(reporte).isEqualTo(new ReconciliationReport.Completed(2, 1, 0, 1, Ending.FINISHED));
        assertThat(estado(1)).isEqualTo(PHONE_REQUESTED);
        assertThat(estado(2)).isEqualTo(EXPIRED);
    }

    @Test
    void cu11_5a_siUnEventoCerroElProcesoMientrasSePreguntabaNoSeHaceNada() {
        proceso(PHONE_SUBMITTED, 1);
        reloj.en(VENCIDO);
        // AppointmentConfirmed llega mientras se espera la lista (que todavía no lo tenía).
        catedra.alListar = () ->
            procesos.changeFromOutside(id(1), proceso -> {
                proceso.confirmed(new ar.edu.um.programacion2.turnos.reservation.domain.model.Confirmation(8251, ANTES), ANTES);
                return proceso;
            });

        ReconciliationReport reporte = casoDeUso().reconcile();

        // Sin la relectura, la decisión (ausente → EXPIRED) habría cerrado un proceso confirmado.
        assertThat(estado(1)).isEqualTo(CONFIRMED);
        assertThat(reporte).isEqualTo(new ReconciliationReport.Completed(1, 0, 1, 0, Ending.FINISHED));
    }

    @Test
    void cu11_5a_siUnRechazoExtendioElPlazoMientrasTantoLaDecisionRecalculadaEsEsperar() {
        // El caso en que recalcular sobre el releído cambia el resultado: el hecho viejo (ausente → EXPIRED) se podría
        // aplicar sobre PHONE_REJECTED, pero con el expiresAt nuevo todavía no corresponde cerrarlo.
        proceso(PHONE_SUBMITTED, 1);
        reloj.en(VENCIDO);
        catedra.alListar = () ->
            procesos.changeFromOutside(id(1), proceso -> {
                proceso.phoneRejected(
                    new ar.edu.um.programacion2.turnos.reservation.domain.model.PhoneRejection("INVALID_PHONE_NUMBER", null),
                    VENCIDO.plus(Duration.ofMinutes(5)),
                    VENCIDO
                );
                return proceso;
            });

        ReconciliationReport reporte = casoDeUso().reconcile();

        assertThat(estado(1)).isEqualTo(PHONE_REJECTED);
        assertThat(reporte).isEqualTo(new ReconciliationReport.Completed(1, 0, 1, 0, Ending.FINISHED));
    }

    @Test
    void elTopeDeProcesosPorEjecucionDejaElRestoParaLaProxima() {
        limites = new ReconciliationLimits(2, Duration.ofMinutes(2));
        proceso(PHONE_REQUESTED, 1);
        proceso(PHONE_SUBMITTED, 2);
        proceso(PHONE_REJECTED, 3);
        reloj.en(VENCIDO);

        ReconciliationReport reporte = casoDeUso().reconcile();

        assertThat(reporte).isEqualTo(new ReconciliationReport.Completed(2, 2, 0, 0, Ending.MAX_PROCESSES));
        assertThat(catedra.listados).hasSize(2);
        assertThat(estado(3)).isEqualTo(PHONE_REJECTED);
    }

    @Test
    void pasadoElPlazoDeLaEjecucionNoSeEmpiezaOtroProceso() {
        limites = new ReconciliationLimits(50, Duration.ofSeconds(30));
        proceso(PHONE_REQUESTED, 1);
        proceso(PHONE_SUBMITTED, 2);
        catedra.tardaCadaListado = Duration.ofSeconds(31);
        reloj.en(VENCIDO);

        ReconciliationReport reporte = casoDeUso().reconcile();

        assertThat(reporte).isEqualTo(new ReconciliationReport.Completed(1, 1, 0, 0, Ending.DEADLINE));
        assertThat(catedra.listados).hasSize(1);
    }

    @Test
    void siInterrumpenElHiloDuranteLaEsperaLaEjecucionTermina() {
        proceso(PHONE_REQUESTED, 1);
        proceso(PHONE_SUBMITTED, 2);
        catedra.fallasDelListado.add(new CatedraUnavailableException("timeout"));
        dormir = espera -> {
            throw new InterruptedException("se apaga la aplicación");
        };
        reloj.en(VENCIDO);

        ReconciliationReport reporte = casoDeUso().reconcile();

        assertThat(reporte).isEqualTo(new ReconciliationReport.Completed(1, 0, 0, 0, Ending.INTERRUPTED));
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(catedra.listados).hasSize(1);
    }

    @Test
    void elResumenVaEnInfoSiCambioAlgoYEnDebugSiNo(CapturedOutput salida) {
        proceso(PHONE_REQUESTED, 1);
        casoDeUso().reconcile();
        assertThat(salida.getOut()).doesNotContain("CU-11: 1 intermedios");

        reloj.en(VENCIDO);
        casoDeUso().reconcile();
        assertThat(salida.getOut()).contains("CU-11: 1 intermedios, 1 revisados, 1 cambiados").contains("PHONE_REQUESTED -> EXPIRED (NOT_FOUND_AFTER_EXPIRY)");
    }

    @Test
    void reconciliarDosVecesSeguidasNoCambiaNadaLaSegunda() {
        proceso(PHONE_REQUESTED, 1);
        catedra.tiene(1, 101, CatedraReservationStatus.CONFIRMED);
        reloj.en(VENCIDO);

        casoDeUso().reconcile();
        List<String> escrituras = procesos.writes();
        ReconciliationReport segunda = casoDeUso().reconcile();

        assertThat(segunda).isEqualTo(new ReconciliationReport.Completed(0, 0, 0, 0, Ending.FINISHED));
        assertThat(procesos.writes()).isEqualTo(escrituras);
    }
}
