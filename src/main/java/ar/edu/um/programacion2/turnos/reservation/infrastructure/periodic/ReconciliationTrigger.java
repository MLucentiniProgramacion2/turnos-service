package ar.edu.um.programacion2.turnos.reservation.infrastructure.periodic;

import ar.edu.um.programacion2.turnos.reservation.application.service.ReservationService;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReconciliationReport;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adaptador de entrada de CU-11: cada ejecución llama a {@link ReservationService}, como los controllers y el listener. Lo
 * programa {@link ReconciliationSchedulingConfiguration}: la primera al arrancar y después cada {@code interval}.
 * <p>
 * <strong>Nunca propaga.</strong> El scheduler de Spring, ante una excepción de una tarea repetida, la loguea y sigue; igual
 * se atrapa acá para que el comportamiento no dependa de eso y el log diga qué pasó (como la periódica del catálogo). Lo
 * esperado (la cátedra caída) ya lo registra el caso de uso; acá solo llega lo inesperado, por ejemplo la base caída.
 */
@Component
@RequiredArgsConstructor
public class ReconciliationTrigger {

    private static final Logger LOG = LoggerFactory.getLogger(ReconciliationTrigger.class);

    private final ReservationService reservationService;

    public void reconcile() {
        try {
            ReconciliationReport report = reservationService.reconcilePendingProcesses();
            switch (report) {
                case ReconciliationReport.Skipped skipped -> LOG.debug("Reconciliación: ya había una en curso");
                case ReconciliationReport.Completed completed -> LOG.trace("Reconciliación terminada: {}", completed);
            }
        } catch (RuntimeException e) {
            LOG.error("Reconciliación: falló de forma inesperada; se vuelve a intentar en la próxima ejecución", e);
        }
    }
}
