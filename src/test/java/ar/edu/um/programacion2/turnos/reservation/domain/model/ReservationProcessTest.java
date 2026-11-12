package ar.edu.um.programacion2.turnos.reservation.domain.model;

import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessMatrix.AHORA;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessMatrix.CANCELACION;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessMatrix.CIERRE_NUEVO;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessMatrix.CONFIRMACION_NUEVA;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessMatrix.EVENTO_NUEVO;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessMatrix.HOLD;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessMatrix.PEDIDO;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessMatrix.PEDIDO_NUEVO;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessMatrix.RECHAZO;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessMatrix.TELEFONO;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessMatrix.TELEFONO_NUEVO;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessMatrix.VENCE;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessMatrix.VENCE_NUEVO;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessMatrix.procesoEn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessMatrix.Caso;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessMatrix.Esperado;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessMatrix.Operacion;
import ar.edu.um.programacion2.turnos.reservation.domain.model.TransitionResult.Applied;
import ar.edu.um.programacion2.turnos.reservation.domain.model.TransitionResult.NoEffect;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ReservationProcessTest {

    private static final long USUARIO = 7;

    static List<Caso> casos() {
        return ReservationProcessMatrix.casos();
    }

    @Test
    void laMatrizCubreTodosLosEstadosPorTodasLasOperaciones() {
        assertThat(casos()).hasSize(ReservationProcessStatus.values().length * Operacion.values().length).hasSize(121);
    }

    /**
     * Cada estado por cada operación: el resultado, el estado y <strong>todos</strong> los datos del proceso después. Un
     * hecho aplicado cambia el estado, {@code statusChangedAt} y solo los datos de ese hecho; uno sin efecto o una
     * excepción no cambian nada (salvo el {@code eventId} enviado, CU-06 7a).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("casos")
    void cadaEstadoPorCadaOperacion(Caso caso) {
        ReservationProcess proceso = procesoEn(caso.estado(), USUARIO);
        Map<String, Object> antes = foto(proceso);

        switch (caso.esperado()) {
            case Esperado.Aplica(ReservationProcessStatus destino) -> {
                assertThat(caso.operacion().aplicar(proceso)).isEqualTo(new Applied(caso.estado(), destino));
                assertThat(foto(proceso)).isEqualTo(despuesDeAplicar(antes, caso.operacion(), destino));
            }
            case Esperado.SinEfecto(NoEffectReason motivo) -> {
                assertThat(caso.operacion().aplicar(proceso)).isEqualTo(new NoEffect(caso.estado(), motivo));
                Map<String, Object> esperada = new LinkedHashMap<>(antes);
                if (caso.operacion() == Operacion.phoneSubmitted) {
                    esperada.put("lastSubmittedEventId", Optional.of(EVENTO_NUEVO));
                }
                assertThat(foto(proceso)).isEqualTo(esperada);
            }
            case Esperado.Excepcion() -> {
                assertThatThrownBy(() -> caso.operacion().aplicar(proceso)).isInstanceOf(IllegalStateException.class);
                assertThat(foto(proceso)).isEqualTo(antes);
            }
        }
    }

    private static Map<String, Object> despuesDeAplicar(Map<String, Object> antes, Operacion operacion, ReservationProcessStatus destino) {
        Map<String, Object> despues = new LinkedHashMap<>(antes);
        despues.put("status", destino);
        despues.put("statusChangedAt", AHORA);
        @SuppressWarnings("unchecked")
        CatedraHold hold = ((Optional<CatedraHold>) antes.get("hold")).orElse(null);
        switch (operacion) {
            case holdCreated -> despues.put("hold", Optional.of(HOLD));
            case confirmAccepted -> despues.put("hold", Optional.of(hold.withExpiresAt(VENCE_NUEVO)));
            case phoneRequested -> {
                despues.put("phoneRequest", Optional.of(PEDIDO_NUEVO));
                despues.put("hold", Optional.of(hold.withExpiresAt(VENCE_NUEVO)));
            }
            case phoneSubmitted -> {
                despues.put("phone", Optional.of(TELEFONO_NUEVO));
                despues.put("lastSubmittedEventId", Optional.of(EVENTO_NUEVO));
            }
            case phoneRejected -> {
                despues.put("rejection", Optional.of(RECHAZO));
                despues.put("hold", Optional.of(hold.withExpiresAt(VENCE_NUEVO)));
            }
            case confirmed -> despues.put("confirmation", Optional.of(CONFIRMACION_NUEVA));
            case expired, invalid, failed -> despues.put("closing", Optional.of(CIERRE_NUEVO));
            case cancelled, cancelledByReconciliation -> despues.put("cancellation", Optional.of(CANCELACION));
        }
        return despues;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Recorridos completos
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void startDejaElProcesoEnCreatingHoldSinDatosDeLaCatedra() {
        ReservationProcess proceso = nuevo();

        assertThat(proceso.getStatus()).isEqualTo(ReservationProcessStatus.CREATING_HOLD);
        assertThat(proceso.getVersion()).isNull();
        assertThat(proceso.getCreatedAt()).isEqualTo(AHORA);
        assertThat(proceso.getStatusChangedAt()).isEqualTo(AHORA);
        assertThat(proceso.getHold()).isEmpty();
        assertThat(proceso.getId().value()).isEqualTo(UUID.fromString("11111111-2222-4333-8444-555555555555"));
    }

    @Test
    void flujoConRechazoReenvioConfirmacionYCancelacion() {
        ReservationProcess proceso = nuevo();
        Instant t = AHORA;

        assertThat(proceso.holdCreated(HOLD, t = t.plusSeconds(1))).isInstanceOf(Applied.class);
        assertThat(proceso.confirmAccepted(VENCE, t = t.plusSeconds(1))).isInstanceOf(Applied.class);
        assertThat(proceso.phoneRequested(PEDIDO, VENCE, t = t.plusSeconds(1))).isInstanceOf(Applied.class);
        assertThat(proceso.phoneSubmitted(TELEFONO, "envio-1", t = t.plusSeconds(1))).isInstanceOf(Applied.class);
        assertThat(proceso.phoneRejected(RECHAZO, VENCE, t = t.plusSeconds(1))).isInstanceOf(Applied.class);
        assertThat(proceso.phoneSubmitted(TELEFONO_NUEVO, "envio-2", t = t.plusSeconds(1))).isInstanceOf(Applied.class);
        assertThat(proceso.confirmed(CONFIRMACION_NUEVA, t = t.plusSeconds(1))).isInstanceOf(Applied.class);
        // Un rechazo tardío del primer envío no reabre un proceso confirmado.
        assertThat(proceso.phoneRejected(RECHAZO, VENCE, t.plusSeconds(1))).isEqualTo(
            new NoEffect(ReservationProcessStatus.CONFIRMED, NoEffectReason.CONFIRMED_ONLY_CANCELS)
        );
        assertThat(proceso.cancelled(CANCELACION, t = t.plusSeconds(1))).isInstanceOf(Applied.class);

        assertThat(proceso.getStatus()).isEqualTo(ReservationProcessStatus.CANCELLED);
        assertThat(proceso.getStatusChangedAt()).isEqualTo(t);
        assertThat(proceso.getPhone()).contains(TELEFONO_NUEVO);
        assertThat(proceso.getLastSubmittedEventId()).contains("envio-2");
        assertThat(proceso.getConfirmation()).contains(CONFIRMACION_NUEVA);
    }

    @Test
    void elPedidoDeTelefonoAntesDel202DejaSinEfectoAl202() {
        ReservationProcess proceso = nuevo();
        proceso.holdCreated(HOLD, AHORA);
        proceso.phoneRequested(PEDIDO, VENCE, AHORA);

        assertThat(proceso.confirmAccepted(VENCE, AHORA)).isEqualTo(
            new NoEffect(ReservationProcessStatus.PHONE_REQUESTED, NoEffectReason.NOT_FORWARD)
        );
    }

    @Test
    void confirmAcceptedSinVencimientoConservaElDelHold() {
        ReservationProcess proceso = nuevo();
        proceso.holdCreated(HOLD, AHORA);

        proceso.confirmAccepted(null, AHORA);

        assertThat(proceso.getHold().orElseThrow().expiresAt()).isEqualTo(VENCE);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Invariantes del constructor
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void cadaEstadoDeLaMatrizCumpleLosInvariantes() {
        for (ReservationProcessStatus estado : ReservationProcessStatus.values()) {
            assertThat(procesoEn(estado, USUARIO).getStatus()).isEqualTo(estado);
        }
    }

    @Test
    void elConstructorRechazaDatosQueNoCorrespondenAlEstado() {
        assertThatThrownBy(() -> conDatos(ReservationProcessStatus.CREATING_HOLD, HOLD, null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("todavía no tiene hold");
        assertThatThrownBy(() -> conDatos(ReservationProcessStatus.EXPIRED, null, null, null, null, null, new Closing("X", null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("datos del hold");
        assertThatThrownBy(() -> conDatos(ReservationProcessStatus.PHONE_REQUESTED, HOLD, null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("pedido de teléfono");
        assertThatThrownBy(() -> conDatos(ReservationProcessStatus.PHONE_SUBMITTED, HOLD, PEDIDO, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("teléfono enviado");
        assertThatThrownBy(() -> conDatos(ReservationProcessStatus.PHONE_SUBMITTED, HOLD, PEDIDO, TELEFONO, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("eventId");
        assertThatThrownBy(() -> conDatos(ReservationProcessStatus.CONFIRMED, HOLD, null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("confirmación");
        assertThatThrownBy(() -> conDatos(ReservationProcessStatus.FAILED, null, null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("motivo del cierre");
    }

    @Test
    void unaVersionNegativaEsInvalida() {
        assertThatThrownBy(() ->
            new ReservationProcess(
                new ReservationProcessId(UUID.randomUUID()),
                -1L,
                new ProcessOwner(USUARIO, "ana"),
                slot(),
                new ProfessionalSnapshot("Ana", "Gomez", "Clínica", 30),
                new PatientName("Juan", "Perez"),
                ReservationProcessStatus.CREATING_HOLD,
                AHORA,
                AHORA,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
            )
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void elToStringNoTieneDatosPersonales() {
        ReservationProcess proceso = procesoEn(ReservationProcessStatus.PHONE_SUBMITTED, USUARIO);

        assertThat(proceso.toString()).doesNotContain("Juan", "Perez", "5551234").contains("PHONE_SUBMITTED");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Ayudas
    // ---------------------------------------------------------------------------------------------------------------

    private static ReservationProcess nuevo() {
        return ReservationProcess.start(
            new ReservationProcessId(UUID.fromString("11111111-2222-4333-8444-555555555555")),
            new ProcessOwner(USUARIO, "ana.perez"),
            slot(),
            new ProfessionalSnapshot("Ana", "Gomez", "Clínica", 30),
            new PatientName("Juan", "Perez"),
            AHORA
        );
    }

    private static AppointmentSlot slot() {
        return new AppointmentSlot(202, LocalDate.parse("2026-12-14"), LocalTime.parse("09:00"), LocalTime.parse("09:30"));
    }

    private static ReservationProcess conDatos(
        ReservationProcessStatus estado,
        CatedraHold hold,
        PhoneRequest pedido,
        PhoneNumber telefono,
        PhoneRejection rechazo,
        Confirmation confirmacion,
        Closing cierre
    ) {
        return new ReservationProcess(
            new ReservationProcessId(UUID.randomUUID()),
            null,
            new ProcessOwner(USUARIO, "ana"),
            slot(),
            new ProfessionalSnapshot("Ana", "Gomez", "Clínica", 30),
            new PatientName("Juan", "Perez"),
            estado,
            AHORA,
            AHORA,
            hold,
            pedido,
            telefono,
            null,
            rechazo,
            confirmacion,
            null,
            cierre
        );
    }

    /** Todos los datos del proceso, para comparar antes y después. La entrada {@code proceso} no se compara. */
    private static Map<String, Object> foto(ReservationProcess p) {
        Map<String, Object> foto = new LinkedHashMap<>();
        foto.put("id", p.getId());
        foto.put("version", p.getVersion());
        foto.put("owner", p.getOwner());
        foto.put("slot", p.getSlot());
        foto.put("professional", p.getProfessional());
        foto.put("patient", p.getPatient());
        foto.put("status", p.getStatus());
        foto.put("createdAt", p.getCreatedAt());
        foto.put("statusChangedAt", p.getStatusChangedAt());
        foto.put("hold", p.getHold());
        foto.put("phoneRequest", p.getPhoneRequest());
        foto.put("phone", p.getPhone());
        foto.put("lastSubmittedEventId", p.getLastSubmittedEventId());
        foto.put("rejection", p.getRejection());
        foto.put("confirmation", p.getConfirmation());
        foto.put("cancellation", p.getCancellation());
        foto.put("closing", p.getClosing());
        return foto;
    }
}
