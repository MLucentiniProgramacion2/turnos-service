package ar.edu.um.programacion2.turnos.reservation.infrastructure.messaging.listener;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.turnos.reservation.infrastructure.config.CatedraKafkaProperties;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.config.ReservationProperties;
import com.zaxxer.hikari.HikariConfig;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * La cuenta del peor caso del listener (ADR 0006) con los valores de la configuración <strong>real</strong>: los YAML de
 * {@code src/main} (no los de {@code src/test}, que los tapan en el classpath) y, si no dicen nada, los valores por
 * defecto de cada clase. Si alguien cambia un plazo, este test recalcula y avisa si deja de cerrar.
 * <p>
 * <strong>Por intento</strong> es lo que cuenta para {@code max.poll.interval.ms}: cada reintento del
 * {@code DefaultErrorHandler} vuelve por otro {@code poll} y arranca un plazo nuevo, pero la espera previa al reintento
 * corre dentro del plazo de esa entrega (medido en el catálogo, ADR 0005). <strong>Por mensaje completo</strong> es cuánto
 * puede tardar en confirmarse un evento que falla siempre.
 * <p>
 * <strong>Supuesto que hace cerrar la cuenta:</strong> la base responde o corta. El plazo de la transacción acota las
 * sentencias (también la espera de un bloqueo), pero no un cuelgue de red sin respuesta ni el {@code commit}: el driver
 * de PostgreSQL no tiene {@code socketTimeout} configurado.
 */
class ReservationEventWorstCaseTest {

    @Test
    void unIntentoEntraEnMaxPollIntervalYElMensajeCompletoTieneCota() {
        Binder principal = binder("src/main/resources/config/application.yml");
        CatedraKafkaProperties kafka = principal.bind("catedra.kafka", CatedraKafkaProperties.class).orElseGet(CatedraKafkaProperties::new);
        ReservationProperties reserva = principal.bind("reservation", ReservationProperties.class).orElseGet(ReservationProperties::new);
        Duration conexion = Duration.ofMillis(
            List.of("src/main/resources/config/application-dev.yml", "src/main/resources/config/application-prod.yml")
                .stream()
                .map(archivo -> binder(archivo).bind("spring.datasource.hikari.connection-timeout", Long.class).orElse(new HikariConfig().getConnectionTimeout()))
                .max(Comparator.naturalOrder())
                .orElseThrow()
        );
        Duration transaccion = reserva.getEventTransactionTimeout();
        List<Duration> esperas = kafka.getRetryDelays();
        Duration esperaMasLarga = esperas.stream().max(Comparator.naturalOrder()).orElseThrow();
        Duration sumaDeEsperas = esperas.stream().reduce(Duration.ZERO, Duration::plus);

        // Los valores de hoy, para que la cuenta del ADR no quede vieja sin que nadie se entere.
        assertThat(conexion).isEqualTo(Duration.ofSeconds(30));
        assertThat(transaccion).isEqualTo(Duration.ofSeconds(10));
        assertThat(esperas).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));

        Duration porIntento = conexion.plus(transaccion).plus(esperaMasLarga);
        Duration porMensaje = conexion.plus(transaccion).multipliedBy(esperas.size() + 1L).plus(sumaDeEsperas);

        assertThat(porIntento).isEqualTo(Duration.ofSeconds(44));
        assertThat(porMensaje).isEqualTo(Duration.ofSeconds(167));
        assertThat(kafka.getMaxPollInterval()).isEqualTo(Duration.ofMinutes(2)).isGreaterThan(porIntento);
    }

    private static Binder binder(String archivo) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(archivo));
        Properties propiedades = yaml.getObject();
        // Las variables de entorno no se resuelven: vale el valor por defecto que pone el YAML después de ":".
        propiedades.replaceAll((clave, valor) -> valor instanceof String texto ? sinVariables(texto) : valor);
        return new Binder(new MapConfigurationPropertySource(propiedades));
    }

    private static String sinVariables(String valor) {
        return valor.replaceAll("\\$\\{[A-Z_]+:([^}]*)}", "$1");
    }
}
