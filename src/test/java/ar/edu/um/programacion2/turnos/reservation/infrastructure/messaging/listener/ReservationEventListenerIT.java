package ar.edu.um.programacion2.turnos.reservation.infrastructure.messaging.listener;

import static ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.ReservationTestDatabase.usuario;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ar.edu.um.programacion2.turnos.IntegrationTest;
import ar.edu.um.programacion2.turnos.repository.UserRepository;
import ar.edu.um.programacion2.turnos.reservation.KafkaTestBroker;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ReservationProcessRepository;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.ReservationTestDatabase;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.entity.ReservationProcessEntity;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.mapper.ReservationProcessMapper;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.repository.JpaReservationProcessRepository;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * CU-05 de punta a punta: Kafka real con Testcontainers, PostgreSQL real y el contenedor del listener de verdad.
 * <p>
 * <strong>Sin esperas a ciegas:</strong> después de publicar se espera a que el grupo confirme el offset, leído del broker
 * con {@code Admin}. Es la señal de que el mensaje ya se procesó y se persistió (el offset se confirma después).
 * <p>
 * <strong>Las propiedades {@code spring.kafka.*} son una trampa a propósito:</strong> si alguna llegara al consumidor, el
 * broker sería uno que no existe y el grupo otro, y los tests fallarían.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(
    properties = {
        "catedra.kafka.listener-enabled=true",
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.consumer.group-id=grupo-que-no-se-usa",
        "spring.kafka.consumer.auto-offset-reset=latest",
        "spring.kafka.consumer.max-poll-records=500",
        "spring.kafka.listener.ack-mode=batch",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.fail-fast=true",
    }
)
class ReservationEventListenerIT {

    private static final String TOPIC = KafkaTestBroker.createTopic("alumnos.turnos.acciones.test-");
    private static final String GRUPO = "alumnos-de-prueba-turnos";
    private static final Duration MAX_ESPERA = Duration.ofSeconds(30);
    private static final String PROCESO = "process-a92b47d0-1";

    /** Mensajes publicados desde que arrancó la clase: el offset que tiene que quedar confirmado. */
    private static long publicados;

    @DynamicPropertySource
    static void brokerDePrueba(DynamicPropertyRegistry properties) {
        properties.add("catedra.kafka.bootstrap-servers", KafkaTestBroker::bootstrapServers);
        properties.add("catedra.kafka.actions-topic", () -> TOPIC);
        properties.add("catedra.kafka.consumer-group", () -> GRUPO);
    }

    @MockitoSpyBean
    private ReservationProcessRepository procesos;

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

    @Autowired
    private PostgreSQLContainer database;

    @BeforeEach
    void unProcesoEsperandoElPedidoDeTelefono() {
        ReservationTestDatabase.limpiar(tx, em);
        long userId = tx.execute(status -> userRepository.saveAndFlush(usuario("reserva-kafka")).getId());
        ReservationProcessEntity entidad = ReservationTestDatabase.fila(mapper, ReservationProcessStatus.WAITING_FOR_PHONE_REQUEST, userId, 1);
        assertThat(entidad.getReservationProcessId()).isEqualTo(PROCESO);
        tx.executeWithoutResult(status -> jpa.saveAndFlush(entidad));
    }

    @AfterEach
    void limpiar() {
        reset(procesos);
        ReservationTestDatabase.limpiar(tx, em);
    }

    @Test
    void cu05_principal_elPedidoDeTelefonoDejaElProcesoEnPhoneRequestedYRegistraElEvento(CapturedOutput salida) {
        String evento = eventoNuevo();

        publicar(PROCESO, pedido(evento, PROCESO));
        esperarQueSeConfirmeTodo();

        assertThat(fila()).isEqualTo("PHONE_REQUESTED|" + evento + "|Ingrese un numero de telefono para continuar.|2026-12-14 12:44:50");
        assertThat(eventos()).containsExactly(evento);
        // El mensaje de la cátedra es para la app: no va al log.
        assertThat(salida.getAll()).contains("AdditionalInformationRequested " + evento).doesNotContain("Ingrese un numero");
    }

    @Test
    void cu05_3_1a_elMismoEventoDosVecesSeAplicaUnaSolaVez() {
        String evento = eventoNuevo();

        publicar(PROCESO, pedido(evento, PROCESO));
        publicar(PROCESO, pedido(evento, PROCESO));
        esperarQueSeConfirmeTodo();

        assertThat(eventos()).containsExactly(evento);
        verify(procesos, times(1)).save(any());
        assertThat(version()).isEqualTo(1);
    }

    @Test
    void cu05_2a_unMensajeRotoNoTrabaLaParticionNiSeRegistra(CapturedOutput salida) {
        String evento = eventoNuevo();

        publicar(PROCESO, "{\"eventId\": rotoInventado");
        publicar(PROCESO, null);
        publicar(PROCESO, pedido(evento, PROCESO).replace(",\"holdId\":\"hold-6c0c2f41-1\"", ""));
        publicar(PROCESO, pedido(evento, PROCESO));
        esperarQueSeConfirmeTodo();

        assertThat(fila()).startsWith("PHONE_REQUESTED|" + evento);
        assertThat(eventos()).containsExactly(evento);
        assertThat(salida.getAll()).contains("mal formado (no es el JSON esperado)", "mal formado (mensaje vacío)", "mal formado (falta holdId)").doesNotContain("rotoInventado");
    }

    @Test
    void unTipoDesconocidoYUnResultadoTodaviaNoProcesadoSeConfirmanSinRegistrarse(CapturedOutput salida) {
        publicar(PROCESO, "{\"eventId\":\"" + eventoNuevo() + "\",\"eventType\":\"OtroTipo\",\"reservationProcessId\":\"" + PROCESO + "\"}");
        publicar(PROCESO, "{\"eventId\":\"" + eventoNuevo() + "\",\"eventType\":\"AppointmentConfirmed\",\"reservationProcessId\":\"" + PROCESO + "\"}");
        esperarQueSeConfirmeTodo();

        assertThat(eventos()).isEmpty();
        assertThat(fila()).startsWith("WAITING_FOR_PHONE_REQUEST|");
        assertThat(salida.getAll()).contains("eventType desconocido", "AppointmentConfirmed", "los resultados todavía no se procesan");
    }

    @Test
    void cu05_3_2a_unProcesoQueNoExisteQuedaRegistradoSinCrearNada() {
        String evento = eventoNuevo();

        publicar("process-de-nadie", pedido(evento, "process-de-nadie"));
        esperarQueSeConfirmeTodo();

        assertThat(eventos()).containsExactly(evento);
        assertThat(contar()).isEqualTo(1);
    }

    /**
     * CU-05 contra CU-04 sobre el mismo proceso: justo cuando el listener va a guardar, "el pedido HTTP" (otra conexión)
     * guarda el {@code 202} del confirm. El listener pierde por {@code @Version}, el {@code DefaultErrorHandler} lo
     * reintenta y el segundo intento lee el estado nuevo y lo avanza.
     */
    @Test
    void cu05_siUnPedidoHttpGanaElGuardadoElListenerReintentaYVeElEstadoNuevo(CapturedOutput salida) {
        jpaEnHoldCreated();
        doAnswer(invocacion -> {
            ejecutarEnOtraConexion("update reservation_process set status = 'WAITING_FOR_PHONE_REQUEST', version = version + 1");
            return invocacion.callRealMethod();
        })
            .doCallRealMethod()
            .when(sinProxy(procesos))
            .save(any());
        String evento = eventoNuevo();

        publicar(PROCESO, pedido(evento, PROCESO));
        esperarQueSeConfirmeTodo();

        assertThat(fila()).startsWith("PHONE_REQUESTED|" + evento);
        assertThat(eventos()).containsExactly(evento);
        assertThat(salida.getAll()).contains("Evento de reserva: falló el intento 1", "ObjectOptimisticLockingFailureException");
        // La fila de prueba arranca en la versión 0; el 202 de "CU-04" la lleva a 1 y el pedido de teléfono, a 2.
        assertThat(version()).isEqualTo(2);
    }

    /** DPT-22: si la falla no se resuelve, 4 intentos (0, 1, 3 y 7 s), ERROR y offset confirmado. Nada queda registrado. */
    @Test
    void cu05_3a_siLaBaseSigueFallandoSeAgotanLosReintentosYSeConfirmaElOffset(CapturedOutput salida) {
        doThrow(new DataAccessResourceFailureException("la base no responde")).when(sinProxy(procesos)).save(any());
        String evento = eventoNuevo();

        publicar(PROCESO, pedido(evento, PROCESO));
        esperarQueSeConfirmeTodo();

        verify(procesos, atLeast(4)).save(any());
        assertThat(eventos()).isEmpty();
        assertThat(fila()).startsWith("WAITING_FOR_PHONE_REQUEST|");
        // Medido: el retry listener avisa también la cuarta falla, justo antes del recoverer; no hay una quinta.
        assertThat(salida.getAll()).contains("falló el intento 4", "Evento de reserva descartado después de los reintentos").doesNotContain("falló el intento 5");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Ayudas
    // ---------------------------------------------------------------------------------------------------------------

    private static String eventoNuevo() {
        return "request-event-" + UUID.randomUUID();
    }

    /** El ejemplo de §15.4 con este proceso. */
    private static String pedido(String eventId, String reservationProcessId) {
        return (
            "{\"eventId\":\"" + eventId + "\",\"eventType\":\"AdditionalInformationRequested\",\"reservationProcessId\":\"" + reservationProcessId +
            "\",\"holdId\":\"hold-6c0c2f41-1\",\"requiredField\":\"PHONE_NUMBER\",\"message\":\"Ingrese un numero de telefono para continuar.\"," +
            "\"expiresAt\":\"2026-12-14T12:44:50Z\",\"occurredAt\":\"2026-12-14T12:35:10Z\",\"schemaVersion\":1}"
        );
    }

    private static void publicar(String clave, String valor) {
        KafkaTestBroker.publish(TOPIC, clave, valor);
        publicados++;
    }

    private static void esperarQueSeConfirmeTodo() {
        await().atMost(MAX_ESPERA).until(() -> Long.valueOf(publicados).equals(KafkaTestBroker.committedOffset(GRUPO, TOPIC)));
    }

    private void jpaEnHoldCreated() {
        tx.executeWithoutResult(status -> em.createNativeQuery("update reservation_process set status = 'HOLD_CREATED'").executeUpdate());
    }

    private static <T> T sinProxy(T espia) {
        return AopTestUtils.getUltimateTargetObject(espia);
    }

    private String fila() {
        return tx.execute(status ->
            (String) em
                .createNativeQuery(
                    "select status || '|' || coalesce(request_event_id, '') || '|' || coalesce(phone_request_message, '') || '|' ||" +
                        " to_char(expires_at, 'YYYY-MM-DD HH24:MI:SS') from reservation_process"
                )
                .getSingleResult()
        );
    }

    private long version() {
        return tx.execute(status -> ((Number) em.createNativeQuery("select version from reservation_process").getSingleResult()).longValue());
    }

    private long contar() {
        return tx.execute(status -> (Long) em.createQuery("select count(p) from ReservationProcessEntity p").getSingleResult());
    }

    @SuppressWarnings("unchecked")
    private List<String> eventos() {
        return tx.execute(status -> (List<String>) em.createNativeQuery("select event_id from processed_event order by processed_at").getResultList());
    }

    private void ejecutarEnOtraConexion(String sql) {
        try (
            Connection otra = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
            Statement statement = otra.createStatement()
        ) {
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Falló el SQL en la otra conexión", e);
        }
    }
}
