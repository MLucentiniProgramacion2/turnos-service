package ar.edu.um.programacion2.turnos.reservation.application;

import static ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.ReservationTestDatabase.fila;
import static ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.ReservationTestDatabase.usuario;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import ar.edu.um.programacion2.turnos.IntegrationTest;
import ar.edu.um.programacion2.turnos.repository.UserRepository;
import ar.edu.um.programacion2.turnos.reservation.domain.model.EventOutcome;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationEvent;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.in.HandlePhoneRequestedUseCase;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ProcessedEventRepository;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ReservationProcessRepository;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.ReservationTestDatabase;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.mapper.ReservationProcessMapper;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.repository.JpaReservationProcessRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * El {@code eventId} y el cambio de estado se confirman juntos o no se confirma ninguno (DPT-15), contra PostgreSQL. Hacen
 * falta los dos tests:
 * <ul>
 * <li>si falla el guardado del proceso, no queda el evento: detecta un registro hecho <strong>antes</strong>, en otra
 * transacción (el reintento lo vería como duplicado y el pedido de teléfono se perdería);</li>
 * <li>si falla el registro del evento, no queda el cambio: detecta un registro hecho <strong>después</strong>, en otra
 * transacción (el reintento aplicaría el evento dos veces).</li>
 * </ul>
 * Los espías se configuran sobre el objeto de adentro del proxy transaccional: sobre el proxy, el {@code MANDATORY} cortaría
 * la llamada del propio {@code doThrow} (visto en el catálogo).
 */
@IntegrationTest
class ReservationEventAtomicityIT {

    private static final Instant VENCE = Instant.parse("2026-12-14T12:44:50Z");

    @Autowired
    private HandlePhoneRequestedUseCase casoDeUso;

    @MockitoSpyBean
    private ReservationProcessRepository procesos;

    @MockitoSpyBean
    private ProcessedEventRepository eventos;

    @Autowired
    private JpaReservationProcessRepository jpa;

    @Autowired
    private ReservationProcessMapper mapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private EntityManager em;

    private String reservationProcessId;

    @BeforeEach
    void guardarUnProcesoEsperandoElPedido() {
        ReservationTestDatabase.limpiar(tx, em);
        long userId = tx.execute(status -> userRepository.saveAndFlush(usuario("reserva-atomica")).getId());
        var entidad = fila(mapper, ReservationProcessStatus.WAITING_FOR_PHONE_REQUEST, userId, 1);
        reservationProcessId = entidad.getReservationProcessId();
        tx.executeWithoutResult(status -> jpa.saveAndFlush(entidad));
    }

    @AfterEach
    void limpiar() {
        ReservationTestDatabase.limpiar(tx, em);
    }

    @Test
    void siFallaElGuardadoDelProcesoElEventoNoQuedaRegistrado() {
        doThrow(new DataAccessResourceFailureException("la base no responde")).when(sinProxy(procesos)).save(any());

        assertThatThrownBy(() -> casoDeUso.handle(pedido())).isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(eventosRegistrados()).isZero();
        assertThat(estado()).isEqualTo("WAITING_FOR_PHONE_REQUEST");
    }

    @Test
    void siFallaElRegistroDelEventoElProcesoNoCambia() {
        doThrow(new DataAccessResourceFailureException("la base no responde")).when(sinProxy(eventos)).registerIfAbsent(anyString(), anyString(), any());

        assertThatThrownBy(() -> casoDeUso.handle(pedido())).isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(estado()).isEqualTo("WAITING_FOR_PHONE_REQUEST");
        assertThat(eventosRegistrados()).isZero();
    }

    @Test
    void sinFallasQuedanLosDosYElSegundoIntentoEsUnDuplicado() {
        assertThat(casoDeUso.handle(pedido())).isInstanceOf(EventOutcome.Applied.class);
        assertThat(casoDeUso.handle(pedido())).isEqualTo(new EventOutcome.Duplicate());

        assertThat(estado()).isEqualTo("PHONE_REQUESTED");
        assertThat(eventosRegistrados()).isEqualTo(1);
        String pedidoGuardado = tx.execute(status ->
            (String) em.createNativeQuery("select request_event_id || '|' || phone_request_message from reservation_process").getSingleResult()
        );
        assertThat(pedidoGuardado).isEqualTo("request-event-8a31|Ingrese un numero de telefono para continuar.");
    }

    /** Un "sin efecto" también se confirma: si no, cada reentrega del evento tardío se volvería a evaluar (DPT-15). */
    @Test
    void unEventoTardioSobreUnFinalQuedaRegistradoSinCambiarElProceso() {
        tx.executeWithoutResult(status ->
            em.createNativeQuery("update reservation_process set status = 'EXPIRED', closing_reason = 'HOLD_EXPIRED', version = version + 1").executeUpdate()
        );

        assertThat(casoDeUso.handle(pedido())).isInstanceOf(EventOutcome.NoEffect.class);

        assertThat(estado()).isEqualTo("EXPIRED");
        assertThat(eventosRegistrados()).isEqualTo(1);
    }

    @Test
    void unEventoDeUnProcesoQueNoExisteQuedaRegistrado() {
        ReservationEvent.PhoneRequested ajeno = new ReservationEvent.PhoneRequested("request-event-ajeno", "process-que-no-existe", "hold-x", null, VENCE);

        assertThat(casoDeUso.handle(ajeno)).isEqualTo(new EventOutcome.ProcessNotFound());

        assertThat(eventosRegistrados()).isEqualTo(1);
    }

    private ReservationEvent.PhoneRequested pedido() {
        return new ReservationEvent.PhoneRequested(
            "request-event-8a31",
            reservationProcessId,
            "hold-6c0c2f41-1",
            "Ingrese un numero de telefono para continuar.",
            VENCE
        );
    }

    private static <T> T sinProxy(T espia) {
        return AopTestUtils.getUltimateTargetObject(espia);
    }

    private String estado() {
        return tx.execute(status -> (String) em.createNativeQuery("select status from reservation_process").getSingleResult());
    }

    private long eventosRegistrados() {
        return tx.execute(status -> (Long) em.createQuery("select count(e) from ProcessedEventEntity e").getSingleResult());
    }
}
