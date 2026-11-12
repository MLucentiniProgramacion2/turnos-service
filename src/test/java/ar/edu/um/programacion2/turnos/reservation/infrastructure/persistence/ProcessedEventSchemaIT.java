package ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.um.programacion2.turnos.IntegrationTest;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.entity.ProcessedEventEntity;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * La clave primaria de {@code processed_event} es la barrera de idempotencia (DPT-15): un {@code eventId} repetido no se
 * puede registrar dos veces, aunque lo intenten dos transacciones.
 * <p>
 * Se inserta con {@code persist} y no con {@code save}: con un ID asignado, {@code save} haría {@code merge}, que sobre una
 * fila existente actualiza en vez de fallar y taparía el duplicado.
 */
@IntegrationTest
class ProcessedEventSchemaIT {

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private EntityManager em;

    @BeforeEach
    @AfterEach
    void limpiar() {
        ReservationTestDatabase.limpiar(tx, em);
    }

    @Test
    void unEventIdRepetidoViolaLaClavePrimaria() {
        registrar("confirmation-event-7d10", "AppointmentConfirmed");

        assertThatThrownBy(() -> registrar("confirmation-event-7d10", "AppointmentConfirmed"))
            .isInstanceOf(ConstraintViolationException.class)
            .extracting(e -> ((ConstraintViolationException) e).getConstraintName())
            .isEqualTo("pk_processed_event");
        Long cantidad = tx.execute(status -> (Long) em.createQuery("select count(e) from ProcessedEventEntity e").getSingleResult());
        assertThat(cantidad).isEqualTo(1);
    }

    @Test
    void eventosDistintosSeRegistranAunqueSeanDelMismoTipo() {
        registrar("confirmation-event-7d10", "AppointmentConfirmed");
        registrar("confirmation-event-7d11", "AppointmentConfirmed");

        Long cantidad = tx.execute(status -> (Long) em.createQuery("select count(e) from ProcessedEventEntity e").getSingleResult());
        assertThat(cantidad).isEqualTo(2);
    }

    private void registrar(String eventId, String eventType) {
        tx.executeWithoutResult(status -> {
            ProcessedEventEntity evento = new ProcessedEventEntity();
            evento.setEventId(eventId);
            evento.setEventType(eventType);
            evento.setProcessedAt(Instant.parse("2026-12-10T12:00:00Z"));
            em.persist(evento);
            em.flush();
        });
    }
}
