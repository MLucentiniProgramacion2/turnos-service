package ar.edu.um.programacion2.turnos.reservation.infrastructure.periodic;

import ar.edu.um.programacion2.turnos.reservation.infrastructure.config.ReservationProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Programa CU-11 con {@code reservation.reconciliation.*}: <strong>una sola tarea {@code fixedDelay} con demora inicial
 * 0</strong>. Los dos disparadores del CU ("arranque del servicio" y "temporizador") son esta tarea: el arranque es su
 * primera ejecución, y el temporizador, las siguientes.
 * <ul>
 * <li><strong>No demora ni impide el arranque:</strong> corre en el hilo del scheduler, no en {@code main}. Si la cátedra no
 * responde, el servicio levanta igual (enunciado §8).</li>
 * <li><strong>La de arranque y la periódica no se pueden superponer:</strong> una tarea {@code fixedDelay} no se superpone
 * consigo misma (la siguiente se cuenta desde que termina la anterior). El lock del caso de uso queda como resguardo.</li>
 * <li><strong>{@link SchedulingConfigurer} y no {@code @Scheduled}</strong>, como la periódica del catálogo: usa el
 * {@link Duration} ya validado (de 10 s a 10 min) y, con {@code enabled=false}, la tarea ni se registra.</li>
 * <li><strong>{@code @EnableScheduling} propio:</strong> el de {@code AsyncConfiguration} de JHipster está apagado en los
 * perfiles de test; sin este, en los tests no habría scheduler. Repetirlo no tiene efecto. El scheduler es el
 * {@code taskScheduler} que arma Spring Boot ({@code spring.task.scheduling.*}).</li>
 * </ul>
 */
@Configuration
@EnableScheduling
public class ReconciliationSchedulingConfiguration implements SchedulingConfigurer {

    private static final Logger LOG = LoggerFactory.getLogger(ReconciliationSchedulingConfiguration.class);

    private final ReservationProperties properties;
    private final ReconciliationTrigger trigger;

    public ReconciliationSchedulingConfiguration(ReservationProperties properties, ReconciliationTrigger trigger) {
        this.properties = properties;
        this.trigger = trigger;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        ReservationProperties.Reconciliation reconciliation = properties.getReconciliation();
        if (!reconciliation.isEnabled()) {
            LOG.info("Reconciliación de procesos pendientes desactivada (reservation.reconciliation.enabled=false)");
            return;
        }
        Duration interval = reconciliation.getInterval();
        registrar.addFixedDelayTask(new FixedDelayTask(trigger::reconcile, interval, Duration.ZERO));
        LOG.info("Reconciliación de procesos pendientes al arrancar y cada {}", interval);
    }
}
