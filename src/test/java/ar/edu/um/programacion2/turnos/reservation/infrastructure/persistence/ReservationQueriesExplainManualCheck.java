package ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence;

import ar.edu.um.programacion2.turnos.IntegrationTest;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <strong>Medición, no test:</strong> ¿las consultas de CU-09, CU-11 y CU-12 necesitan un índice nuevo? Carga 10 000
 * procesos de 1000 usuarios (10 por usuario, a lo sumo un intermedio cada uno, como exige el índice parcial) en el
 * PostgreSQL de Testcontainers y muestra el {@code EXPLAIN ANALYZE} de cada una. No corre en el build (el nombre no es
 * {@code *Test} ni {@code *IT}) y además pide la propiedad:
 *
 * <pre>
 * ./mvnw test -Dtest=ReservationQueriesExplainManualCheck -Dmedicion=true -Dspring.profiles.active=test,testdev
 * </pre>
 *
 * Las sentencias son las que arma Hibernate para cada consulta (mismo filtro, mismo orden), escritas en SQL para poder
 * pedir el plan.
 */
@IntegrationTest
@EnabledIfSystemProperty(named = "medicion", matches = "true")
class ReservationQueriesExplainManualCheck {

    private static final String TODOS =
        "('CREATING_HOLD','HOLD_CREATED','WAITING_FOR_PHONE_REQUEST','PHONE_REQUESTED','PHONE_SUBMITTED','PHONE_REJECTED','CONFIRMED','CANCELLED','EXPIRED','INVALID','FAILED')";
    private static final String INTERMEDIOS =
        "('CREATING_HOLD','HOLD_CREATED','WAITING_FOR_PHONE_REQUEST','PHONE_REQUESTED','PHONE_SUBMITTED','PHONE_REJECTED')";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate tx;

    @AfterEach
    void limpiar() {
        // Hikari tiene auto-commit apagado (JHipster): sin transacción, lo escrito se deshace al devolver la conexión.
        tx.executeWithoutResult(status -> {
            jdbc.update("delete from reservation_process");
            jdbc.update("delete from jhi_user where login like 'medicion-%'");
        });
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void planesDeLasConsultasNuevasCon10000Procesos() {
        tx.executeWithoutResult(status -> cargar());
        jdbc.execute("analyze reservation_process");
        long usuario = jdbc.queryForObject("select min(id) + 500 from jhi_user where login like 'medicion-%'", Long.class);

        explicar(
            "CU-09 página (select)",
            "select * from reservation_process where user_id = " +
            usuario +
            " and status in " +
            TODOS +
            " order by appointment_date desc, start_time desc, id asc offset 0 limit 20"
        );
        explicar("CU-09 página (count)", "select count(id) from reservation_process where user_id = " + usuario + " and status in " + TODOS);
        explicar("CU-11 intermedios", "select * from reservation_process where status in " + INTERMEDIOS + " order by status_changed_at");
        explicar("CU-12 todos (select)", "select * from reservation_process order by status_changed_at desc, id offset 0 limit 20");
        explicar("CU-12 todos (count)", "select count(id) from reservation_process");
        explicar(
            "CU-12 por usuario",
            "select * from reservation_process where user_id = " + usuario + " order by status_changed_at desc, id offset 0 limit 20"
        );
        explicar(
            "CU-12 por estado",
            "select * from reservation_process where status in ('CONFIRMED') order by status_changed_at desc, id offset 0 limit 20"
        );
    }

    private void cargar() {
        int usuarios = jdbc.update(
            """
            insert into jhi_user (id, login, password_hash, first_name, last_name, email, activated, lang_key, created_by, last_modified_by)
            select nextval('sequence_generator'), 'medicion-' || g, repeat('x', 60), 'Ana', 'Perez', 'medicion-' || g || '@example.com', true, 'es', 'medicion', 'medicion'
            from generate_series(1, 1000) g
            """
        );
        System.out.println("Usuarios: " + usuarios);
        // Por usuario: 9 finales o confirmados y un intermedio (PHONE_REQUESTED) en la mitad de los usuarios.
        jdbc.update(
            """
            insert into reservation_process (id, version, user_id, user_login, professional_id, appointment_date, start_time, end_time,
              professional_first_name, professional_last_name, professional_category_name, slot_duration_minutes,
              patient_first_name, patient_last_name, status, hold_id, reservation_process_id, expires_at,
              reservation_id, confirmed_at, closing_reason, created_at, status_changed_at)
            select gen_random_uuid(), 0, u.id, u.login, 202, date '2026-12-01' + (n % 60), time '08:00' + (n % 20) * interval '30 minutes',
              time '08:30' + (n % 20) * interval '30 minutes', 'Martin', 'Rivas', 'Clinica', 30, 'Ana', 'Perez',
              case when n = 10 and u.id % 2 = 0 then 'PHONE_REQUESTED' when n % 3 = 0 then 'CONFIRMED' else 'EXPIRED' end,
              'hold-' || u.id || '-' || n, 'process-' || u.id || '-' || n, timestamp '2026-11-20 12:00' + n * interval '1 hour',
              case when n % 3 = 0 and not (n = 10 and u.id % 2 = 0) then u.id * 100 + n end,
              case when n % 3 = 0 and not (n = 10 and u.id % 2 = 0) then timestamp '2026-11-20 12:00' end,
              case when not (n % 3 = 0) and not (n = 10 and u.id % 2 = 0) then 'HOLD_EXPIRED' end,
              timestamp '2026-11-20 11:00' + (u.id % 1000) * interval '1 minute', timestamp '2026-11-20 11:00' + ((u.id * 7 + n) % 5000) * interval '1 minute'
            from jhi_user u cross join generate_series(1, 10) n
            where u.login like 'medicion-%'
            """
        );
        System.out.println("Filas: " + jdbc.queryForObject("select count(*) from reservation_process", Long.class));
    }

    private void explicar(String nombre, String sql) {
        List<String> plan = tx.execute(status -> jdbc.queryForList("explain (analyze, buffers, costs off) " + sql, String.class));
        System.out.println("\n=== " + nombre + " ===\n" + String.join("\n", plan));
    }
}
