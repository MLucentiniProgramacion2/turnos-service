package ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence;

import ar.edu.um.programacion2.turnos.config.Constants;
import ar.edu.um.programacion2.turnos.domain.User;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessMatrix;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.entity.ReservationProcessEntity;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.mapper.ReservationProcessMapper;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Datos de prueba de la feature en PostgreSQL. Los tests usan {@code User} y la base de JHipster para crear los dueños de
 * los procesos (la FK lo exige): en los tests no rigen las reglas de ArchUnit.
 */
public final class ReservationTestDatabase {

    private ReservationTestDatabase() {}

    /** Un usuario de JHipster listo para guardar. */
    public static User usuario(String login) {
        User usuario = new User();
        usuario.setLogin(login);
        usuario.setPassword("$2a$10$".concat("x".repeat(53)));
        usuario.setFirstName("Ana");
        usuario.setLastName("Pérez");
        usuario.setEmail(login + "@example.com");
        usuario.setLangKey(Constants.DEFAULT_LANGUAGE);
        usuario.setActivated(true);
        return usuario;
    }

    /**
     * Una fila nueva de un proceso en ese estado, con los datos que exige el estado. {@code n} hace únicos el {@code id},
     * el {@code hold_id} y el {@code reservation_process_id}, para poder guardar varias filas del mismo usuario.
     */
    public static ReservationProcessEntity fila(ReservationProcessMapper mapper, ReservationProcessStatus estado, long userId, int n) {
        ReservationProcessEntity fila = mapper.toEntity(ReservationProcessMatrix.procesoEn(estado, userId));
        fila.setId(UUID.fromString("00000000-0000-4000-9000-" + "%012d".formatted(n)));
        if (fila.getHoldId() != null) {
            fila.setHoldId(fila.getHoldId() + "-" + n);
            fila.setReservationProcessId(fila.getReservationProcessId() + "-" + n);
        }
        return fila;
    }

    /** Borra los procesos, los eventos procesados y los usuarios de prueba (los que no son de los datos iniciales). */
    public static void limpiar(TransactionTemplate tx, EntityManager em) {
        tx.executeWithoutResult(status -> {
            em.createNativeQuery("delete from reservation_process").executeUpdate();
            em.createNativeQuery("delete from jhi_user_authority where user_id in (select id from jhi_user where login like 'reserva-%')").executeUpdate();
            em.createNativeQuery("delete from jhi_user where login like 'reserva-%'").executeUpdate();
        });
    }
}
