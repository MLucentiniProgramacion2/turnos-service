package ar.edu.um.programacion2.turnos.reservation.infrastructure.config;

import ar.edu.um.programacion2.turnos.reservation.domain.model.AvailabilityPolicy;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
}
