package ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence;

import static ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.ReservationTestDatabase.fila;
import static ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.ReservationTestDatabase.usuario;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.um.programacion2.turnos.IntegrationTest;
import ar.edu.um.programacion2.turnos.repository.UserRepository;
import ar.edu.um.programacion2.turnos.reservation.domain.model.Confirmation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessMatrix;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.programacion2.turnos.reservation.domain.model.TransitionResult;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.entity.ReservationProcessEntity;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.mapper.ReservationProcessMapper;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.repository.JpaReservationProcessRepository;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * La tabla {@code reservation_process} contra PostgreSQL real: que la entidad valide contra ella (si no, el contexto ni
 * arranca, porque Hibernate está en {@code validate}), las restricciones, el índice único parcial (DPT-17), el bloqueo
 * optimista (DPT-09), las horas sin corrimiento de zona y la FK hacia {@code jhi_user}.
 */
@IntegrationTest
class ReservationProcessSchemaIT {

    private static final String INDICE_PARCIAL = "uq_reservation_process_one_intermediate_per_user";

    @Autowired
    private JpaReservationProcessRepository repository;

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

    private long userId;

    @BeforeEach
    void crearUsuario() {
        ReservationTestDatabase.limpiar(tx, em);
        userId = tx.execute(status -> userRepository.saveAndFlush(usuario("reserva-schema")).getId());
    }

    @AfterEach
    void limpiar() {
        ReservationTestDatabase.limpiar(tx, em);
    }

    @Test
    void unProcesoSeGuardaYSeLeeIgual() {
        ReservationProcess original = ReservationProcessMatrix.procesoEn(ReservationProcessStatus.PHONE_REJECTED, userId);

        guardar(mapper.toEntity(original));
        ReservationProcess leido = tx.execute(status -> mapper.toDomain(repository.findById(original.getId().value()).orElseThrow()));

        assertThat(leido.getVersion()).isZero();
        assertThat(leido).usingRecursiveComparison().ignoringFields("version").isEqualTo(original);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Índice único parcial (DPT-17)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Recorre el enum, no una lista escrita a mano: si se agrega o se renombra un estado intermedio sin cambiar el índice
     * (o el {@code CHECK}), este test falla con el nombre del estado.
     */
    @ParameterizedTest(name = "dos procesos en {0}")
    @EnumSource(ReservationProcessStatus.class)
    void dosProcesosDelMismoUsuarioEnElMismoEstado(ReservationProcessStatus estado) {
        guardar(fila(mapper, estado, userId, 1));

        if (estado.isIntermediate()) {
            assertThat(violacion(() -> guardar(fila(mapper, estado, userId, 2)))).isEqualTo(INDICE_PARCIAL);
        } else {
            guardar(fila(mapper, estado, userId, 2));
            assertThat(repository.count()).isEqualTo(2);
        }
    }

    @Test
    void dosIntermediosDistintosDelMismoUsuarioTambienViolanElIndice() {
        guardar(fila(mapper, ReservationProcessStatus.CREATING_HOLD, userId, 1));

        assertThat(violacion(() -> guardar(fila(mapper, ReservationProcessStatus.PHONE_REJECTED, userId, 2)))).isEqualTo(INDICE_PARCIAL);
    }

    @Test
    void unIntermedioConVariosConfirmadosYFinalesSePermite() {
        guardar(fila(mapper, ReservationProcessStatus.PHONE_SUBMITTED, userId, 1));
        guardar(fila(mapper, ReservationProcessStatus.CONFIRMED, userId, 2));
        guardar(fila(mapper, ReservationProcessStatus.CONFIRMED, userId, 3));
        guardar(fila(mapper, ReservationProcessStatus.EXPIRED, userId, 4));
        guardar(fila(mapper, ReservationProcessStatus.FAILED, userId, 5));

        assertThat(repository.count()).isEqualTo(5);
    }

    @Test
    void dosUsuariosPuedenTenerCadaUnoSuProcesoIntermedio() {
        long otro = tx.execute(status -> userRepository.saveAndFlush(usuario("reserva-otro")).getId());

        guardar(fila(mapper, ReservationProcessStatus.HOLD_CREATED, userId, 1));
        guardar(fila(mapper, ReservationProcessStatus.HOLD_CREATED, otro, 2));

        assertThat(repository.count()).isEqualTo(2);
    }

    /** La lista del índice, leída de la base, es exactamente la de los intermedios del enum. */
    @Test
    void laCondicionDelIndiceTieneExactamenteLosIntermediosDelEnum() {
        String definicion = tx.execute(status ->
            (String) em
                .createNativeQuery("select indexdef from pg_indexes where tablename = 'reservation_process' and indexname = ?1")
                .setParameter(1, INDICE_PARCIAL)
                .getSingleResult()
        );

        assertThat(definicion).startsWith("CREATE UNIQUE INDEX").contains("(user_id)");
        assertThat(valoresEntreComillas(definicion.substring(definicion.indexOf("WHERE")))).isEqualTo(
            nombres(ReservationProcessStatus.intermediates().toArray(ReservationProcessStatus[]::new))
        );
    }

    @Test
    void elCheckDeStatusTieneExactamenteLosValoresDelEnum() {
        String definicion = tx.execute(status ->
            (String) em
                .createNativeQuery("select pg_get_constraintdef(oid) from pg_constraint where conname = 'ck_reservation_process_status'")
                .getSingleResult()
        );

        assertThat(valoresEntreComillas(definicion)).isEqualTo(nombres(ReservationProcessStatus.values()));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // CHECK, UNIQUE y FK
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void unEstadoQueNoEstaEnElEnumViolaElCheck() {
        guardar(fila(mapper, ReservationProcessStatus.CONFIRMED, userId, 1));

        assertThat(violacionSql("update reservation_process set status = 'RENOMBRADO'")).contains("ck_reservation_process_status");
    }

    @Test
    void losChecksDeLosDatosPorEstado() {
        guardar(fila(mapper, ReservationProcessStatus.CONFIRMED, userId, 1));

        assertThat(violacionSql("update reservation_process set reservation_id = null, confirmed_at = null")).contains(
            "ck_reservation_process_confirmation"
        );
        assertThat(violacionSql("update reservation_process set hold_id = null")).contains("ck_reservation_process_hold_all_or_none");
        assertThat(
            violacionSql("update reservation_process set hold_id = null, reservation_process_id = null, expires_at = null")
        ).contains("ck_reservation_process_hold_by_status");
        assertThat(violacionSql("update reservation_process set end_time = start_time")).contains("ck_reservation_process_time_range");
        assertThat(violacionSql("update reservation_process set slot_duration_minutes = 0")).contains(
            "ck_reservation_process_slot_duration"
        );
    }

    @Test
    void elHoldIdYElReservationProcessIdDeLaCatedraSonUnicos() {
        ReservationProcessEntity primera = fila(mapper, ReservationProcessStatus.CONFIRMED, userId, 1);
        guardar(primera);
        ReservationProcessEntity mismoHold = fila(mapper, ReservationProcessStatus.CONFIRMED, userId, 2);
        mismoHold.setHoldId(primera.getHoldId());
        ReservationProcessEntity mismoProceso = fila(mapper, ReservationProcessStatus.CONFIRMED, userId, 3);
        mismoProceso.setReservationProcessId(primera.getReservationProcessId());

        assertThat(violacion(() -> guardar(mismoHold))).isEqualTo("uq_reservation_process_hold_id");
        assertThat(violacion(() -> guardar(mismoProceso))).isEqualTo("uq_reservation_process_reservation_process_id");
    }

    @Test
    void elDuenoTieneQueSerUnUsuarioQueExiste() {
        assertThat(violacion(() -> guardar(fila(mapper, ReservationProcessStatus.CREATING_HOLD, 999_999, 1)))).isEqualTo(
            "fk_reservation_process_user"
        );
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Bloqueo optimista (DPT-09)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Dos transacciones modifican el mismo proceso: CU-11 lo leyó en {@code HOLD_CREATED} y, mientras consultaba a la
     * cátedra, un evento de Kafka lo pasó a {@code PHONE_REQUESTED}. La del evento va por una conexión aparte
     * ({@link DriverManager}), porque el pool de los tests tiene una sola conexión. Gana la que confirma primero; la otra
     * recibe la excepción del bloqueo optimista en vez de pisar el cambio.
     */
    @Test
    void dosTransaccionesSobreElMismoProcesoGanaLaPrimeraYLaOtraRecibeElBloqueoOptimista() {
        ReservationProcess guardado = ReservationProcessMatrix.procesoEn(ReservationProcessStatus.HOLD_CREATED, userId);
        guardar(mapper.toEntity(guardado));
        ReservationProcess deCu11 = tx.execute(status -> mapper.toDomain(repository.findById(guardado.getId().value()).orElseThrow()));

        ejecutarEnOtraConexion(
            "update reservation_process set status = 'PHONE_REQUESTED', request_event_id = 'request-event-8a31', version = version + 1"
        );
        TransitionResult resultado = deCu11.confirmed(new Confirmation(8251, ReservationProcessMatrix.AHORA), ReservationProcessMatrix.AHORA);
        assertThat(resultado).isInstanceOf(TransitionResult.Applied.class);
        ReservationProcessEntity cambioDeCu11 = mapper.toEntity(deCu11);

        assertThatThrownBy(() -> guardar(cambioDeCu11)).isInstanceOf(OptimisticLockingFailureException.class);
        ReservationProcessEntity enLaBase = tx.execute(status -> repository.findAll().getFirst());
        assertThat(enLaBase.getStatus()).isEqualTo(ReservationProcessStatus.PHONE_REQUESTED);
        assertThat(enLaBase.getVersion()).isEqualTo(1);
    }

    @Test
    void cadaActualizacionSumaUnoALaVersion() {
        ReservationProcess proceso = ReservationProcessMatrix.procesoEn(ReservationProcessStatus.HOLD_CREATED, userId);
        guardar(mapper.toEntity(proceso));

        ReservationProcess leido = tx.execute(status -> mapper.toDomain(repository.findById(proceso.getId().value()).orElseThrow()));
        leido.confirmAccepted(null, ReservationProcessMatrix.AHORA);
        guardar(mapper.toEntity(leido));

        Long version = tx.execute(status -> repository.findById(proceso.getId().value()).orElseThrow().getVersion());
        assertThat(version).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Horas locales sin corrimiento de zona
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Con la JVM en -03 y {@code hibernate.jdbc.time_zone: UTC}, sin {@code @JdbcTypeCode(SqlTypes.LOCAL_TIME)} las 22:00
     * se guardaban como 01:00. Se lee con SQL ({@code ::text}): leer con JPA haría la conversión inversa y lo taparía.
     */
    @Test
    void lasHorasSeGuardanTalCualConLaJvmEnMenosTres() {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("America/Argentina/Mendoza"));
        try {
            ReservationProcessEntity nocturno = fila(mapper, ReservationProcessStatus.CREATING_HOLD, userId, 1);
            nocturno.setStartTime(LocalTime.of(22, 0));
            nocturno.setEndTime(LocalTime.of(22, 30));
            guardar(nocturno);

            String guardadas = tx.execute(status ->
                (String) em.createNativeQuery("select start_time::text || '-' || end_time::text from reservation_process").getSingleResult()
            );
            assertThat(guardadas).isEqualTo("22:00:00-22:30:00");
            ReservationProcessEntity leida = tx.execute(status -> repository.findById(nocturno.getId()).orElseThrow());
            assertThat(leida.getStartTime()).isEqualTo(LocalTime.of(22, 0));
            assertThat(leida.getEndTime()).isEqualTo(LocalTime.of(22, 30));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Ayudas
    // ---------------------------------------------------------------------------------------------------------------

    private void guardar(ReservationProcessEntity entidad) {
        tx.executeWithoutResult(status -> repository.saveAndFlush(entidad));
    }

    /** El nombre de la restricción que violó la operación. */
    private static String violacion(Runnable operacion) {
        try {
            operacion.run();
        } catch (DataIntegrityViolationException e) {
            Throwable causa = e.getCause();
            assertThat(causa).isInstanceOf(ConstraintViolationException.class);
            return ((ConstraintViolationException) causa).getConstraintName();
        }
        throw new AssertionError("La operación no violó ninguna restricción");
    }

    /** El mensaje de PostgreSQL al ejecutar SQL que tiene que violar una restricción (incluye su nombre). */
    private String violacionSql(String sql) {
        try {
            ejecutarEnOtraConexion(sql);
        } catch (IllegalStateException e) {
            return e.getCause().getMessage();
        }
        throw new AssertionError("El SQL no violó ninguna restricción: " + sql);
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

    private static Set<String> valoresEntreComillas(String sql) {
        Set<String> valores = new TreeSet<>();
        Matcher matcher = Pattern.compile("'([A-Z_]+)'").matcher(sql);
        while (matcher.find()) {
            valores.add(matcher.group(1));
        }
        return valores;
    }

    private static Set<String> nombres(ReservationProcessStatus... estados) {
        return new TreeSet<>(Arrays.stream(estados).map(Enum::name).toList());
    }
}
