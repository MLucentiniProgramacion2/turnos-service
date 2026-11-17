package ar.edu.um.programacion2.turnos.reservation.domain.ports.in;

import ar.edu.um.programacion2.turnos.reservation.domain.model.ReconciliationReport;

/** CU-11: llevar a un estado correcto los procesos intermedios, con los identificadores persistidos. */
public interface ReconcilePendingProcessesUseCase {
    /** Una ejecución completa; si ya hay una en curso, se saltea sin esperar (1a). */
    ReconciliationReport reconcile();
}
