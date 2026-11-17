package ar.edu.um.programacion2.turnos.reservation.infrastructure.config;

import ar.edu.um.programacion2.turnos.reservation.application.usecases.EventTransactions;
import ar.edu.um.programacion2.turnos.reservation.application.usecases.ReadTransactions;
import ar.edu.um.programacion2.turnos.reservation.domain.model.AvailabilityPolicy;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessId;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.EventIdGenerator;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ReservationProcessIdGenerator;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Arma lo que los casos de uso necesitan y depende de la configuración: el reloj y las reglas de fechas.
 * {@code application} no puede depender de esta clase ni de las propiedades (ArchUnit), así que recibe los objetos ya
 * armados, como en el catálogo ({@code SyncConfiguration}).
 */
@Configuration
@EnableConfigurationProperties(ReservationProperties.class)
public class ReservationConfiguration {

    /**
     * El "ahora" de la reserva, en UTC: la zona de atención se aplica en {@link AvailabilityPolicy}. Los tests lo
     * reemplazan por un reloj fijo. {@code ServiceTokenIssuer} no lo usa: tiene el suyo, para que un test que mueve este
     * reloj no emita tokens vencidos.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public AvailabilityPolicy availabilityPolicy(ReservationProperties properties) {
        return new AvailabilityPolicy(properties.getTimeZone(), properties.getBookingHorizonDays());
    }

    /** El identificador de cada proceso nuevo: un UUID v4 aleatorio (decisión del #10). Los tests ponen uno fijo. */
    @Bean
    public ReservationProcessIdGenerator reservationProcessIdGenerator() {
        return () -> new ReservationProcessId(UUID.randomUUID());
    }

    /** Las transacciones de los eventos de Kafka, con el plazo de {@code reservation.event-transaction-timeout}. */
    @Bean
    public EventTransactions eventTransactions(PlatformTransactionManager transactionManager, ReservationProperties properties) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setTimeout(Math.toIntExact(properties.getEventTransactionTimeout().toSeconds()));
        return new EventTransactions(template);
    }

    /**
     * Las transacciones de las consultas (CU-08, CU-09 y CU-12): solo lectura y {@code REPEATABLE_READ}, así una página y su
     * total salen de la misma foto.
     */
    @Bean
    public ReadTransactions readTransactions(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return new ReadTransactions(template);
    }

    /** El {@code eventId} de cada evento que publica turnos: un UUID aleatorio, nuevo en cada envío (contrato §15.5). */
    @Bean
    public EventIdGenerator eventIdGenerator() {
        return () -> UUID.randomUUID().toString();
    }
}
