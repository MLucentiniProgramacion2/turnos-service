package ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence;

import static ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.ReservationTestDatabase.fila;
import static ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.ReservationTestDatabase.usuario;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.um.programacion2.turnos.IntegrationTest;
import ar.edu.um.programacion2.turnos.repository.UserRepository;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessId;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessMatrix;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.IntermediateProcessConflictException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ReservationProcessRepository;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.entity.ReservationProcessEntity;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.mapper.ReservationProcessMapper;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * El adaptador de persistencia del proceso contra PostgreSQL real: el alta con el índice único parcial (también con dos
 * escritores a la vez), el bloqueo optimista y las búsquedas de CU-04 y CU-06.
 */
@IntegrationTest
class JpaReservationProcessRepositoryAdapterIT {

    private static final LocalDate FECHA = LocalDate.parse("2026-12-14");
    private static final LocalTime NUEVE = LocalTime.parse("09:00");

    @Autowired
    private ReservationProcessRepository adaptador;

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

    private long ana;
    private long beto;

    @BeforeEach
    void crearUsuarios() {
        ReservationTestDatabase.limpiar(tx, em);
        ana = tx.execute(status -> userRepository.saveAndFlush(usuario("reserva-ana")).getId());
        beto = tx.execute(status -> userRepository.saveAndFlush(usuario("reserva-beto")).getId());
    }

    @AfterEach
    void limpiar() {
        ReservationTestDatabase.limpiar(tx, em);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Alta
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void elAltaGuardaElProcesoYDevuelveSuVersion() {
        ReservationProcess nuevo = proceso(ReservationProcessStatus.CREATING_HOLD, ana, 1);

        ReservationProcess guardado = tx.execute(status -> adaptador.insert(nuevo));

        assertThat(guardado.getVersion()).isZero();
        assertThat(guardado.getId()).isEqualTo(nuevo.getId());
        assertThat(porId(nuevo.getId(), ana)).get().satisfies(leido -> {
            assertThat(leido.getStatus()).isEqualTo(ReservationProcessStatus.CREATING_HOLD);
            assertThat(leido.getSlot()).isEqualTo(nuevo.getSlot());
            assertThat(leido.getVersion()).isZero();
        });
    }

    /** Con {@code merge}, el segundo alta con el mismo id actualizaría la fila en silencio. Con {@code persist} choca. */
    @Test
    void elAltaEsUnInsertYNoUnMergeUnIdRepetidoChocaYNoPisaLaFila() {
        tx.executeWithoutResult(status -> adaptador.insert(proceso(ReservationProcessStatus.EXPIRED, ana, 1)));
        ReservationProcess mismoId = mapper.toDomain(conId(fila(mapper, ReservationProcessStatus.FAILED, ana, 2), idDe(1)));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> adaptador.insert(mismoId)))
            .isInstanceOf(DataIntegrityViolationException.class)
            .isNotInstanceOf(IntermediateProcessConflictException.class);

        assertThat(estadoEnLaBase(idDe(1))).isEqualTo("EXPIRED");
    }

    @Test
    void cu04_7a_unSegundoIntermedioDelMismoUsuarioEsElConflictoDelPuertoYNoQuedaGuardado() {
        tx.executeWithoutResult(status -> adaptador.insert(proceso(ReservationProcessStatus.PHONE_REQUESTED, ana, 1)));
        ReservationProcess segundo = proceso(ReservationProcessStatus.CREATING_HOLD, ana, 2);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> adaptador.insert(segundo))).isInstanceOf(IntermediateProcessConflictException.class);

        assertThat(contar()).isEqualTo(1);
        // Después del choque, una transacción nueva encuentra al que ganó.
        assertThat(intermedio(ana)).get().extracting(ReservationProcess::getId).isEqualTo(new ReservationProcessId(idDe(1)));
    }

    @Test
    void otraViolacionNoSeConfundeConElConflictoUnUsuarioQueNoExisteSubeSinTraducir() {
        ReservationProcess sinDueno = proceso(ReservationProcessStatus.CREATING_HOLD, 999_999, 1);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> adaptador.insert(sinDueno)))
            .isInstanceOf(DataIntegrityViolationException.class)
            .isNotInstanceOf(IntermediateProcessConflictException.class)
            .hasStackTraceContaining("fk_reservation_process_user");
    }

    @Test
    void otroUsuarioPuedeTenerSuPropioIntermedioYUnoMismoVariosCerrados() {
        tx.executeWithoutResult(status -> {
            adaptador.insert(proceso(ReservationProcessStatus.CREATING_HOLD, ana, 1));
            adaptador.insert(proceso(ReservationProcessStatus.CREATING_HOLD, beto, 2));
            adaptador.insert(proceso(ReservationProcessStatus.CONFIRMED, ana, 3));
            adaptador.insert(proceso(ReservationProcessStatus.FAILED, ana, 4));
        });

        assertThat(contar()).isEqualTo(4);
    }

    /**
     * La carrera del doble toque con dos escritores reales. El primero inserta por una conexión aparte
     * ({@link DriverManager}: el pool de los tests tiene una sola) y deja su transacción abierta; el alta del adaptador
     * queda esperando en el índice único. Cuando el primero confirma, el segundo recibe el conflicto.
     */
    @Test
    void cu04_7a_conDosEscritoresALaVezElSegundoEsperaYRecibeElConflictoCuandoElPrimeroConfirma() throws Exception {
        try (Connection primero = otraConexion(); ExecutorService hilo = Executors.newSingleThreadExecutor()) {
            primero.setAutoCommit(false);
            insertarIntermedio(primero, idDe(1), ana);

            Future<ReservationProcess> segundo = hilo.submit(() -> tx.execute(status -> adaptador.insert(proceso(ReservationProcessStatus.CREATING_HOLD, ana, 2))));
            esperarAQueAlguienEspereUnBloqueo();
            assertThat(segundo.isDone()).isFalse();
            primero.commit();

            assertThatThrownBy(() -> segundo.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(IntermediateProcessConflictException.class);
        }
        assertThat(contar()).isEqualTo(1);
        assertThat(estadoEnLaBase(idDe(1))).isEqualTo("CREATING_HOLD");
    }

    /** Si el primero deshace su transacción, el segundo no chocó con nada: su alta entra. */
    @Test
    void cu04_7a_siElPrimerEscritorDeshaceSuTransaccionElSegundoGuarda() throws Exception {
        try (Connection primero = otraConexion(); ExecutorService hilo = Executors.newSingleThreadExecutor()) {
            primero.setAutoCommit(false);
            insertarIntermedio(primero, idDe(1), ana);

            Future<ReservationProcess> segundo = hilo.submit(() -> tx.execute(status -> adaptador.insert(proceso(ReservationProcessStatus.CREATING_HOLD, ana, 2))));
            esperarAQueAlguienEspereUnBloqueo();
            primero.rollback();

            assertThat(segundo.get(10, TimeUnit.SECONDS).getVersion()).isZero();
        }
        assertThat(contar()).isEqualTo(1);
        assertThat(estadoEnLaBase(idDe(2))).isEqualTo("CREATING_HOLD");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Modificación
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void guardarUnCambioSumaUnoALaVersionYDevuelveElProcesoListoParaElSiguiente() {
        ReservationProcess creando = tx.execute(status -> adaptador.insert(proceso(ReservationProcessStatus.CREATING_HOLD, ana, 1)));

        creando.holdCreated(ReservationProcessMatrix.HOLD, ReservationProcessMatrix.AHORA);
        ReservationProcess conHold = tx.execute(status -> adaptador.save(creando));
        conHold.confirmAccepted(ReservationProcessMatrix.VENCE_NUEVO, ReservationProcessMatrix.AHORA);
        ReservationProcess esperando = tx.execute(status -> adaptador.save(conHold));

        assertThat(conHold.getVersion()).isEqualTo(1);
        assertThat(esperando.getVersion()).isEqualTo(2);
        assertThat(estadoEnLaBase(idDe(1))).isEqualTo("WAITING_FOR_PHONE_REQUEST");
    }

    @Test
    void guardarSobreUnaLecturaViejaLanzaElBloqueoOptimistaYNoPisaElCambio() {
        ReservationProcess leido = tx.execute(status -> adaptador.insert(proceso(ReservationProcessStatus.HOLD_CREATED, ana, 1)));
        ejecutarEnOtraConexion("update reservation_process set status = 'PHONE_REQUESTED', request_event_id = 'request-event-8a31', version = version + 1");

        leido.confirmAccepted(ReservationProcessMatrix.VENCE_NUEVO, ReservationProcessMatrix.AHORA);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> adaptador.save(leido))).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(estadoEnLaBase(idDe(1))).isEqualTo("PHONE_REQUESTED");
    }

    @Test
    void altaYModificacionNoSeConfunden() {
        // Son errores de programación. El proxy de @Repository traduce el IllegalArgumentException a la excepción de Spring.
        ReservationProcess nuevo = proceso(ReservationProcessStatus.CREATING_HOLD, ana, 1);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> adaptador.save(nuevo))).isInstanceOf(InvalidDataAccessApiUsageException.class);

        ReservationProcess guardado = tx.execute(status -> adaptador.insert(nuevo));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> adaptador.insert(guardado))).isInstanceOf(InvalidDataAccessApiUsageException.class);
    }

    @Test
    void sinUnaTransaccionAbiertaElAdaptadorNoTrabaja() {
        assertThatThrownBy(() -> adaptador.insert(proceso(ReservationProcessStatus.CREATING_HOLD, ana, 1))).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> adaptador.findIntermediateByUser(ana)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> adaptador.findByIdAndOwner(new ReservationProcessId(idDe(1)), ana)).isInstanceOf(IllegalTransactionStateException.class);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Búsquedas
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void elIntermedioDelUsuarioNoEsUnConfirmadoNiUnCerradoNiElDeOtroUsuario() {
        tx.executeWithoutResult(status -> {
            adaptador.insert(proceso(ReservationProcessStatus.CONFIRMED, ana, 1));
            adaptador.insert(proceso(ReservationProcessStatus.EXPIRED, ana, 2));
            adaptador.insert(proceso(ReservationProcessStatus.PHONE_SUBMITTED, beto, 3));
        });

        assertThat(intermedio(ana)).isEmpty();
        assertThat(intermedio(beto)).get().extracting(ReservationProcess::getStatus).isEqualTo(ReservationProcessStatus.PHONE_SUBMITTED);
    }

    @Test
    void elProcesoDelMismoTurnoEsElIntermedioOElConfirmadoPeroNoUnCerrado() {
        tx.executeWithoutResult(status -> {
            adaptador.insert(proceso(ReservationProcessStatus.FAILED, ana, 1));
            adaptador.insert(proceso(ReservationProcessStatus.CANCELLED, ana, 2));
        });
        assertThat(buscarPorTurno(ana, 202, FECHA, NUEVE)).isEmpty();

        tx.executeWithoutResult(status -> adaptador.insert(proceso(ReservationProcessStatus.CONFIRMED, ana, 3)));
        assertThat(buscarPorTurno(ana, 202, FECHA, NUEVE)).isEqualTo("CONFIRMED");

        // Con un confirmado y un intermedio del mismo turno, primero el intermedio.
        tx.executeWithoutResult(status -> adaptador.insert(proceso(ReservationProcessStatus.HOLD_CREATED, ana, 4)));
        assertThat(buscarPorTurno(ana, 202, FECHA, NUEVE)).isEqualTo("HOLD_CREATED");
    }

    @Test
    void elProcesoDelMismoTurnoExigeElMismoUsuarioProfesionalFechaYHora() {
        tx.executeWithoutResult(status -> adaptador.insert(proceso(ReservationProcessStatus.HOLD_CREATED, ana, 1)));

        assertThat(buscarPorTurno(ana, 202, FECHA, NUEVE)).isEqualTo("HOLD_CREATED");
        assertThat(buscarPorTurno(beto, 202, FECHA, NUEVE)).isEmpty();
        assertThat(buscarPorTurno(ana, 201, FECHA, NUEVE)).isEmpty();
        assertThat(buscarPorTurno(ana, 202, FECHA.plusDays(7), NUEVE)).isEmpty();
        assertThat(buscarPorTurno(ana, 202, FECHA, LocalTime.parse("09:30"))).isEmpty();
    }

    /**
     * Con la JVM en -03 y {@code hibernate.jdbc.time_zone: UTC}, una hora mal convertida en el parámetro buscaría las 01:00
     * en vez de las 22:00. La fila se escribe con SQL, para que la búsqueda no se compense con el mismo error al guardar.
     */
    @Test
    void laHoraDelTurnoSeBuscaSinCorrerlaDeZonaConLaJvmEnMenosTres() throws Exception {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("America/Argentina/Mendoza"));
        try (Connection conexion = otraConexion()) {
            insertarIntermedio(conexion, idDe(1), ana);
            ejecutarEnOtraConexion("update reservation_process set start_time = '22:00', end_time = '22:30'");

            assertThat(buscarPorTurno(ana, 202, FECHA, LocalTime.of(22, 0))).isEqualTo("CREATING_HOLD");
            assertThat(buscarPorTurno(ana, 202, FECHA, LocalTime.of(1, 0))).isEmpty();
            assertThat(buscarPorTurno(ana, 202, FECHA, LocalTime.of(19, 0))).isEmpty();
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void unEventoEncuentraElProcesoPorElReservationProcessIdDeLaCatedra() {
        tx.executeWithoutResult(status -> {
            adaptador.insert(proceso(ReservationProcessStatus.CREATING_HOLD, ana, 1));
            adaptador.insert(proceso(ReservationProcessStatus.WAITING_FOR_PHONE_REQUEST, beto, 2));
        });

        Optional<ReservationProcess> delSegundo = tx.execute(status -> adaptador.findByReservationProcessId("process-a92b47d0-2"));
        Optional<ReservationProcess> deNadie = tx.execute(status -> adaptador.findByReservationProcessId("process-a92b47d0"));

        assertThat(delSegundo).get().extracting(ReservationProcess::getId).isEqualTo(new ReservationProcessId(idDe(2)));
        assertThat(deNadie).isEmpty();
    }

    @Test
    void cu06_2b_unProcesoSeEncuentraPorSuIdSoloSiEsDeEseUsuario() {
        tx.executeWithoutResult(status -> adaptador.insert(proceso(ReservationProcessStatus.PHONE_REQUESTED, ana, 1)));
        ReservationProcessId id = new ReservationProcessId(idDe(1));

        assertThat(porId(id, ana)).isPresent();
        assertThat(porId(id, beto)).isEmpty();
        assertThat(porId(new ReservationProcessId(idDe(99)), ana)).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Ayudas
    // ---------------------------------------------------------------------------------------------------------------

    /** Un proceso nuevo (sin versión) en ese estado, del profesional 202, el 14/12/2026 a las 09:00. */
    private ReservationProcess proceso(ReservationProcessStatus estado, long userId, int n) {
        return mapper.toDomain(fila(mapper, estado, userId, n));
    }

    private static ReservationProcessEntity conId(ReservationProcessEntity fila, UUID id) {
        fila.setId(id);
        return fila;
    }

    private static UUID idDe(int n) {
        return UUID.fromString("00000000-0000-4000-9000-" + "%012d".formatted(n));
    }

    private Optional<ReservationProcess> porId(ReservationProcessId id, long userId) {
        return tx.execute(status -> adaptador.findByIdAndOwner(id, userId));
    }

    private Optional<ReservationProcess> intermedio(long userId) {
        return tx.execute(status -> adaptador.findIntermediateByUser(userId));
    }

    private String buscarPorTurno(long userId, long profesional, LocalDate fecha, LocalTime hora) {
        return tx.execute(status -> adaptador.findActiveBySlot(userId, profesional, fecha, hora).map(proceso -> proceso.getStatus().name()).orElse(""));
    }

    private long contar() {
        return tx.execute(status -> (Long) em.createQuery("select count(p) from ReservationProcessEntity p").getSingleResult());
    }

    private String estadoEnLaBase(UUID id) {
        return tx.execute(status ->
            (String) em.createNativeQuery("select status from reservation_process where id = :id").setParameter("id", id).getSingleResult()
        );
    }

    private Connection otraConexion() throws SQLException {
        return DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
    }

    private static void insertarIntermedio(Connection conexion, UUID id, long userId) throws SQLException {
        try (Statement statement = conexion.createStatement()) {
            statement.executeUpdate(
                "insert into reservation_process (id, version, user_id, user_login, professional_id, appointment_date, start_time, end_time," +
                    " professional_first_name, professional_last_name, professional_category_name, slot_duration_minutes," +
                    " patient_first_name, patient_last_name, status, created_at, status_changed_at)" +
                    " values ('" + id + "', 0, " + userId + ", 'reserva-ana', 202, '2026-12-14', '09:00', '09:30'," +
                    " 'Ana', 'Gomez', 'Clinica', 30, 'Juan', 'Perez', 'CREATING_HOLD', now(), now())"
            );
        }
    }

    /**
     * Espera, sin {@code Thread.sleep}, a que PostgreSQL informe una sesión esperando un bloqueo: es el alta del otro hilo,
     * detenida en el índice único. Cada vuelta es una consulta a {@code pg_stat_activity}; hay un tope de 10 segundos.
     */
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

    private void ejecutarEnOtraConexion(String sql) {
        try (Connection otra = otraConexion(); Statement statement = otra.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Falló el SQL en la otra conexión", e);
        }
    }
}
