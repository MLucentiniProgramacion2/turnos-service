package ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence;

import static ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.ReservationTestDatabase.fila;
import static ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.ReservationTestDatabase.usuario;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.um.programacion2.turnos.IntegrationTest;
import ar.edu.um.programacion2.turnos.repository.UserRepository;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PageQuery;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ResultPage;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ReservationProcessRepository;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.entity.ReservationProcessEntity;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.mapper.ReservationProcessMapper;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** La página de "Mis reservas" (CU-09) contra PostgreSQL: orden total, filtro, dueño y cuántas sentencias hace. */
@IntegrationTest
class JpaReservationProcessPageIT {

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

    @Test
    void ordenTotalFechaYHoraDescendentesYElIdDesempata() {
        guardar(ReservationProcessStatus.EXPIRED, ana, 1, "2026-12-14", "09:00");
        guardar(ReservationProcessStatus.CONFIRMED, ana, 2, "2026-12-15", "09:00");
        guardar(ReservationProcessStatus.FAILED, ana, 3, "2026-12-14", "11:30");
        // Mismo turno que el 3: un FAILED y un CONFIRMED (se volvió a reservar). El id desempata.
        guardar(ReservationProcessStatus.CONFIRMED, ana, 4, "2026-12-14", "11:30");
        guardar(ReservationProcessStatus.EXPIRED, ana, 5, "2026-12-14", "10:00");

        ResultPage<ReservationProcess> pagina = pagina(ana, Set.of(), 0, 10);

        assertThat(numeros(pagina)).containsExactly(2, 3, 4, 5, 1);
        assertThat(pagina.totalElements()).isEqualTo(5);
    }

    @Test
    void lasPaginasSiguenElMismoOrdenYElTotalEsElDeTodas() {
        for (int n = 1; n <= 7; n++) {
            guardar(ReservationProcessStatus.EXPIRED, ana, n, "2026-12-14", "%02d:00".formatted(8 + n));
        }

        assertThat(numeros(pagina(ana, Set.of(), 0, 3))).containsExactly(7, 6, 5);
        assertThat(numeros(pagina(ana, Set.of(), 1, 3))).containsExactly(4, 3, 2);
        ResultPage<ReservationProcess> ultima = pagina(ana, Set.of(), 2, 3);
        assertThat(numeros(ultima)).containsExactly(1);
        assertThat(ultima.totalElements()).isEqualTo(7);
        assertThat(ultima.totalPages()).isEqualTo(3);
        ResultPage<ReservationProcess> fuera = pagina(ana, Set.of(), 5, 3);
        assertThat(fuera.items()).isEmpty();
        assertThat(fuera.totalElements()).isEqualTo(7);
    }

    @Test
    void soloLosDelDuenoYLosEstadosPedidos() {
        guardar(ReservationProcessStatus.CONFIRMED, ana, 1, "2026-12-14", "09:00");
        guardar(ReservationProcessStatus.CANCELLED, ana, 2, "2026-12-14", "10:00");
        guardar(ReservationProcessStatus.EXPIRED, ana, 3, "2026-12-14", "11:00");
        guardar(ReservationProcessStatus.CONFIRMED, beto, 4, "2026-12-14", "12:00");

        assertThat(numeros(pagina(ana, Set.of(ReservationProcessStatus.CONFIRMED, ReservationProcessStatus.CANCELLED), 0, 10))).containsExactly(2, 1);
        assertThat(numeros(pagina(beto, Set.of(), 0, 10))).containsExactly(4);
        assertThat(pagina(ana, Set.of(ReservationProcessStatus.INVALID), 0, 10).totalElements()).isZero();
    }

    @Test
    void unaPaginaLlenaSonDosSentenciasYUnaIncompletaEnLaPrimeraEsUna() {
        for (int n = 1; n <= 5; n++) {
            guardar(ReservationProcessStatus.EXPIRED, ana, n, "2026-12-14", "%02d:00".formatted(8 + n));
        }

        // Página llena: el select y el count. Primera página incompleta: Spring Data ya sabe el total sin contar.
        assertThat(sentencias(() -> pagina(ana, Set.of(), 0, 2))).isEqualTo(2);
        assertThat(sentencias(() -> pagina(ana, Set.of(), 0, 10))).isEqualTo(1);
    }

    @Test
    void exigeUnaTransaccionAbierta() {
        assertThatThrownBy(() -> adaptador.findPageByOwner(ana, Set.of(), new PageQuery(0, 10))).isInstanceOf(IllegalTransactionStateException.class);
    }

    private ResultPage<ReservationProcess> pagina(long userId, Set<ReservationProcessStatus> estados, int numero, int tamano) {
        return tx.execute(status -> adaptador.findPageByOwner(userId, estados, new PageQuery(numero, tamano)));
    }

    private long sentencias(Supplier<?> accion) {
        Statistics estadisticas = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        estadisticas.setStatisticsEnabled(true);
        try {
            estadisticas.clear();
            accion.get();
            return estadisticas.getPrepareStatementCount();
        } finally {
            estadisticas.setStatisticsEnabled(false);
        }
    }

    private void guardar(ReservationProcessStatus estado, long userId, int n, String fecha, String hora) {
        ReservationProcessEntity entidad = fila(mapper, estado, userId, n);
        entidad.setAppointmentDate(LocalDate.parse(fecha));
        entidad.setStartTime(LocalTime.parse(hora));
        entidad.setEndTime(LocalTime.parse(hora).plusMinutes(30));
        tx.executeWithoutResult(status -> adaptador.insert(mapper.toDomain(entidad)));
    }

    /** El {@code n} de cada proceso de la página: es el final de su id (ver {@code ReservationTestDatabase.fila}). */
    private static List<Integer> numeros(ResultPage<ReservationProcess> pagina) {
        return pagina.items().stream().map(proceso -> proceso.getId().value()).map(UUID::toString).map(id -> Integer.parseInt(id.substring(24))).toList();
    }
}
