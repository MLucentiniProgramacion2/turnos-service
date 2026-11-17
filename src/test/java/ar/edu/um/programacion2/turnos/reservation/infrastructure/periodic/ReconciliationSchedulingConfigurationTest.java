package ar.edu.um.programacion2.turnos.reservation.infrastructure.periodic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ar.edu.um.programacion2.turnos.reservation.application.service.ReservationService;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.config.ReservationProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/** Cómo se programa CU-11: una tarea {@code fixedDelay} con demora inicial 0, o ninguna si está apagada. */
@ExtendWith(OutputCaptureExtension.class)
class ReconciliationSchedulingConfigurationTest {

    private final ReservationProperties propiedades = new ReservationProperties();
    private final ReservationService servicio = mock(ReservationService.class);
    private final ReconciliationTrigger disparador = new ReconciliationTrigger(servicio);

    @Test
    void registraUnaSolaTareaFixedDelayQueArrancaYaYSeRepiteCadaIntervalo() {
        propiedades.getReconciliation().setInterval(Duration.ofSeconds(45));
        ScheduledTaskRegistrar registro = new ScheduledTaskRegistrar();

        new ReconciliationSchedulingConfiguration(propiedades, disparador).configureTasks(registro);

        assertThat(registro.getFixedDelayTaskList()).singleElement().satisfies(tarea -> {
            FixedDelayTask fija = (FixedDelayTask) tarea;
            assertThat(fija.getIntervalDuration()).isEqualTo(Duration.ofSeconds(45));
            // La primera ejecución es la del arranque: demora inicial 0.
            assertThat(fija.getInitialDelayDuration()).isEqualTo(Duration.ZERO);
            fija.getRunnable().run();
        });
        assertThat(registro.getFixedRateTaskList()).isEmpty();
        assertThat(registro.getCronTaskList()).isEmpty();
        verify(servicio).reconcilePendingProcesses();
    }

    @Test
    void apagadaNoRegistraNada() {
        propiedades.getReconciliation().setEnabled(false);
        ScheduledTaskRegistrar registro = new ScheduledTaskRegistrar();

        new ReconciliationSchedulingConfiguration(propiedades, disparador).configureTasks(registro);

        assertThat(registro.hasTasks()).isFalse();
    }

    @Test
    void elDisparadorNuncaPropagaUnaFallaInesperada(CapturedOutput salida) {
        when(servicio.reconcilePendingProcesses()).thenThrow(new IllegalStateException("la base no responde"));

        disparador.reconcile();

        assertThat(salida.getOut()).contains("falló de forma inesperada");
    }
}
