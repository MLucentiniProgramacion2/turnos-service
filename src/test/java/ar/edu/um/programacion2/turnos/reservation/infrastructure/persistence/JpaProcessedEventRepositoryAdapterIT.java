package ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.um.programacion2.turnos.IntegrationTest;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ProcessedEventRepository;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * La barrera de idempotencia (DPT-15) contra PostgreSQL: {@code insert ... on conflict do nothing} en una sola sentencia,
 * también con dos entregas del mismo evento al mismo tiempo (la otra por una conexión aparte, porque el pool de los tests
 * tiene una sola).
 */
@IntegrationTest
class JpaProcessedEventRepositoryAdapterIT {

    private static final Instant AHORA = Instant.parse("2026-12-14T12:30:00Z");

    @Autowired
    private ProcessedEventRepository barrera;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager em;

    @Autowired
    private PostgreSQLContainer database;

    @BeforeEach
    @AfterEach
    void limpiar() {
        ReservationTestDatabase.limpiar(tx, em);
    }

    @Test
    void laPrimeraVezRegistraYLaSegundaDiceQueYaEstaba() {
        assertThat(registrar("request-event-8a31")).isTrue();
        assertThat(registrar("request-event-8a31")).isFalse();

        assertThat(fila("request-event-8a31")).isEqualTo("AdditionalInformationRequested|2026-12-14 12:30:00");
    }

    @Test
    void unDuplicadoNoDeshaceLaTransaccionDeQuienLlama() {
        registrar("request-event-8a31");

        tx.executeWithoutResult(status -> {
            assertThat(barrera.registerIfAbsent("request-event-8a31", "AdditionalInformationRequested", AHORA)).isFalse();
            assertThat(barrera.registerIfAbsent("otro-evento", "AppointmentConfirmed", AHORA)).isTrue();
        });

        assertThat(contar()).isEqualTo(2);
    }

    @Test
    void siLaTransaccionSeDeshaceElEventoNoQuedaRegistrado() {
        tx.executeWithoutResult(status -> {
            barrera.registerIfAbsent("request-event-8a31", "AdditionalInformationRequested", AHORA);
            status.setRollbackOnly();
        });

        assertThat(contar()).isZero();
    }

    /** Dos entregas simultáneas: la segunda espera a la primera y, cuando la primera confirma, ve el duplicado. */
    @Test
    void conDosEntregasALaVezLaSegundaEsperaYVeElDuplicadoCuandoLaPrimeraConfirma() throws Exception {
        try (Connection primera = otraConexion(); ExecutorService hilo = Executors.newSingleThreadExecutor()) {
            primera.setAutoCommit(false);
            insertarEnOtraConexion(primera, "request-event-8a31");

            Future<Boolean> segunda = hilo.submit(() -> registrar("request-event-8a31"));
            esperarAQueAlguienEspereUnBloqueo();
            assertThat(segunda.isDone()).isFalse();
            primera.commit();

            assertThat(segunda.get(10, TimeUnit.SECONDS)).isFalse();
        }
        assertThat(contar()).isEqualTo(1);
    }

    @Test
    void conDosEntregasALaVezSiLaPrimeraSeDeshaceLaSegundaRegistra() throws Exception {
        try (Connection primera = otraConexion(); ExecutorService hilo = Executors.newSingleThreadExecutor()) {
            primera.setAutoCommit(false);
            insertarEnOtraConexion(primera, "request-event-8a31");

            Future<Boolean> segunda = hilo.submit(() -> registrar("request-event-8a31"));
            esperarAQueAlguienEspereUnBloqueo();
            primera.rollback();

            assertThat(segunda.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(fila("request-event-8a31")).startsWith("AdditionalInformationRequested|");
    }

    /**
     * El plazo de la transacción de los eventos (ADR 0006) acota también la espera de un bloqueo: si la otra entrega no
     * termina nunca, esta no queda colgada. Medido con un plazo de 1 s.
     */
    @Test
    void elPlazoDeLaTransaccionCortaLaEsperaDeUnBloqueo() throws Exception {
        TransactionTemplate conPlazo = new TransactionTemplate(transactionManager);
        conPlazo.setTimeout(1);
        try (Connection primera = otraConexion()) {
            primera.setAutoCommit(false);
            insertarEnOtraConexion(primera, "request-event-8a31");

            long inicio = System.nanoTime();
            assertThatThrownBy(() -> conPlazo.execute(status -> barrera.registerIfAbsent("request-event-8a31", "AdditionalInformationRequested", AHORA)))
                .isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
            long milisegundos = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio);
            assertThat(milisegundos).isBetween(900L, 5_000L);
            primera.rollback();
        }
    }

    @Test
    void sinUnaTransaccionAbiertaLaBarreraNoTrabaja() {
        assertThatThrownBy(() -> barrera.registerIfAbsent("request-event-8a31", "AdditionalInformationRequested", AHORA)).isInstanceOf(
            IllegalTransactionStateException.class
        );
    }

    private boolean registrar(String eventId) {
        return tx.execute(status -> barrera.registerIfAbsent(eventId, "AdditionalInformationRequested", AHORA));
    }

    private long contar() {
        return tx.execute(status -> (Long) em.createQuery("select count(e) from ProcessedEventEntity e").getSingleResult());
    }

    private String fila(String eventId) {
        return tx.execute(status ->
            (String) em
                .createNativeQuery("select event_type || '|' || to_char(processed_at, 'YYYY-MM-DD HH24:MI:SS') from processed_event where event_id = :id")
                .setParameter("id", eventId)
                .getSingleResult()
        );
    }

    private Connection otraConexion() throws SQLException {
        return DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
    }

    private static void insertarEnOtraConexion(Connection conexion, String eventId) throws SQLException {
        try (Statement statement = conexion.createStatement()) {
            statement.executeUpdate("insert into processed_event (event_id, event_type, processed_at) values ('" + eventId + "', 'AdditionalInformationRequested', now())");
        }
    }

    /** Espera, sin {@code Thread.sleep}, a que PostgreSQL informe una sesión esperando un bloqueo. Tope de 10 s. */
    private void esperarAQueAlguienEspereUnBloqueo() throws SQLException {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (Connection observador = otraConexion(); Statement statement = observador.createStatement()) {
            while (System.nanoTime() < limite) {
                try (ResultSet resultado = statement.executeQuery("select count(*) from pg_stat_activity where wait_event_type = 'Lock'")) {
                    resultado.next();
                    if (resultado.getInt(1) > 0) {
                        return;
                    }
                }
                Thread.onSpinWait();
            }
        }
        throw new AssertionError("Ninguna sesión quedó esperando un bloqueo");
    }
}
