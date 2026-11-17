package ar.edu.um.programacion2.turnos.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ar.edu.um.programacion2.turnos.IntegrationTest;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.client.FakeHttpServer;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * §8 del enunciado: <strong>reinicio con una operación pendiente</strong>. Un proceso quedó en {@code HOLD_CREATED} (el
 * confirm no respondió y el servicio se apagó). Al arrancar, la primera ejecución de CU-11 lo retoma sola: le pregunta a la
 * cátedra, que no tiene el confirm, y lo reintenta con los datos guardados.
 * <p>
 * El proceso se inserta <strong>durante</strong> el arranque, después de Liquibase y antes de que arranque el scheduler
 * (un bean que depende de {@code liquibase}): así la ejecución de arranque lo encuentra ya en la base, como después de un
 * reinicio.
 * <p>
 * Al terminar, se cancela la tarea programada: el contexto queda en la caché de Spring y, con la reconciliación prendida,
 * reconciliaría cada 60 s los procesos de las otras clases de test. No se cierra el contexto con {@code @DirtiesContext}:
 * eso detiene el contenedor de PostgreSQL que comparten los demás contextos (medido en el #14).
 */
@IntegrationTest
@Import(ReconciliationAtStartupIT.ProcesoQueQuedoPendiente.class)
@TestPropertySource(properties = "reservation.reconciliation.enabled=true")
class ReconciliationAtStartupIT {

    private static final UUID PROCESO = UUID.fromString("00000000-0000-4000-9000-00000000a001");
    private static final FakeHttpServer CATEDRA = iniciar();

    @DynamicPropertySource
    static void catedra(DynamicPropertyRegistry registry) {
        registry.add("catedra.api.url", () -> CATEDRA.baseUrl().toString());
    }

    @AfterAll
    static void detener() {
        CATEDRA.close();
    }

    /** Lo que había en la base antes del reinicio: un usuario y su proceso en HOLD_CREATED, de hace 5 minutos. */
    @TestConfiguration
    static class ProcesoQueQuedoPendiente {

        @Bean
        @DependsOn("liquibase")
        InitializingBean procesoPendienteAntesDelArranque(DataSource dataSource) {
            return () -> {
                Instant ahora = Instant.now();
                try (Connection conexion = dataSource.getConnection()) {
                    conexion.setAutoCommit(false);
                    long usuario;
                    try (
                        PreparedStatement alta = conexion.prepareStatement(
                            "insert into jhi_user (id, login, password_hash, first_name, last_name, email, activated, lang_key, created_by) " +
                            "values (nextval('sequence_generator'), 'reserva-reinicio', ?, 'Ana', 'Pérez', 'reserva-reinicio@example.com', true, 'es', 'test') returning id"
                        )
                    ) {
                        alta.setString(1, "$2a$10$" + "x".repeat(53));
                        var resultado = alta.executeQuery();
                        resultado.next();
                        usuario = resultado.getLong(1);
                    }
                    try (
                        PreparedStatement proceso = conexion.prepareStatement(
                            "insert into reservation_process (id, version, user_id, user_login, professional_id, appointment_date, start_time, end_time," +
                            " professional_first_name, professional_last_name, professional_category_name, slot_duration_minutes," +
                            " patient_first_name, patient_last_name, status, hold_id, reservation_process_id, expires_at, created_at, status_changed_at)" +
                            " values (?, 0, ?, 'reserva-reinicio', 202, date '2027-01-05', time '10:00', time '10:30', 'Martin', 'Rivas', 'Clinica', 30," +
                            " 'Ana', 'Pérez', 'HOLD_CREATED', 'hold-reinicio', 'process-reinicio', ?, ?, ?)"
                        )
                    ) {
                        proceso.setObject(1, PROCESO);
                        proceso.setLong(2, usuario);
                        // Las columnas son "timestamp" en UTC (hibernate.jdbc.time_zone: UTC): un Timestamp.from(...) se
                        // convertiría con la zona de la JVM y el proceso aparecería vencido hace horas.
                        proceso.setObject(3, LocalDateTime.ofInstant(ahora.plus(Duration.ofMinutes(5)), ZoneOffset.UTC));
                        proceso.setObject(4, LocalDateTime.ofInstant(ahora.minus(Duration.ofMinutes(5)), ZoneOffset.UTC));
                        proceso.setObject(5, LocalDateTime.ofInstant(ahora.minus(Duration.ofMinutes(5)), ZoneOffset.UTC));
                        proceso.executeUpdate();
                    }
                    conexion.commit();
                } catch (SQLException e) {
                    throw new IllegalStateException("No se pudo dejar el proceso pendiente", e);
                }
            };
        }
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ScheduledTaskHolder tareasProgramadas;

    @AfterEach
    void cancelarLaReconciliacionProgramada() {
        tareasProgramadas.getScheduledTasks().forEach(ScheduledTask::cancel);
    }

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate tx;

    @Test
    void laEjecucionDeArranqueRetomaUnHoldCreatedQueQuedoDeAntes() {
        await()
            .atMost(Duration.ofSeconds(30))
            .until(() -> "WAITING_FOR_PHONE_REQUEST".equals(tx.execute(status -> jdbc.queryForObject("select status from reservation_process where id = ?", String.class, PROCESO))));

        // Preguntó a la cátedra con los IDs guardados y, como no tenía el confirm, lo reintentó con los datos guardados.
        assertThat(CATEDRA.requestsTo("/api/appointments")).singleElement().satisfies(pedido -> assertThat(pedido.query()).contains("from=2027-01-05&to=2027-01-05"));
        assertThat(CATEDRA.requestsTo("/confirm")).singleElement().satisfies(pedido -> {
            assertThat(pedido.path()).isEqualTo("/api/appointment-holds/hold-reinicio/confirm");
            assertThat(pedido.body()).contains("\"patientFirstName\":\"Ana\"").contains("\"patientLastName\":\"Pérez\"");
        });
        // Nunca el POST del hold (DPT-06).
        assertThat(CATEDRA.requests()).noneMatch(pedido -> pedido.path().equals("/api/appointment-holds"));

        tx.executeWithoutResult(status -> {
            jdbc.update("delete from reservation_process where id = ?", PROCESO);
            jdbc.update("delete from jhi_user where login = 'reserva-reinicio'");
        });
    }

    private static FakeHttpServer iniciar() {
        try {
            FakeHttpServer catedra = FakeHttpServer.start();
            // Antes de que arranque el contexto: la primera ejecución corre apenas arranca el scheduler.
            catedra.route("/api/appointments", 200, "[]", Map.of("X-Total-Count", "0"));
            catedra.routeJson(
                "/confirm",
                202,
                "{\"reservationProcessId\":\"process-reinicio\",\"status\":\"WAITING_FOR_PHONE\",\"expiresAt\":\"" + Instant.now().plus(Duration.ofMinutes(5)) + "\"}"
            );
            return catedra;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
