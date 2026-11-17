package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.util.Objects;

/** El resultado de una ejecución de CU-11. Es {@code sealed}: el disparador decide con un {@code switch} completo. */
public sealed interface ReconciliationReport {
    /** Por qué terminó la ejecución. */
    enum Ending {
        /** Revisó todos los intermedios. */
        FINISHED,
        /** 4b: la cátedra rechazó la credencial técnica. Los demás fallarían igual. */
        ACCESS_DENIED,
        /** 4c: un proceso agotó los reintentos porque la cátedra no responde. Los demás costarían lo mismo. */
        CATEDRA_UNAVAILABLE,
        /** Se llegó al tope de procesos que le preguntan a la cátedra: el resto, a la próxima. */
        MAX_PROCESSES,
        /** Pasó el plazo de la ejecución: el resto, a la próxima. */
        DEADLINE,
        /** Interrumpieron el hilo (se está apagando la aplicación). */
        INTERRUPTED,
    }

    /** 1a: ya había una ejecución en curso; esta se salteó sin esperar. */
    record Skipped() implements ReconciliationReport {}

    /**
     * @param reviewed intermedios revisados
     * @param changed cuántos cambiaron de estado
     * @param waiting cuántos se revisaron y siguen esperando (todavía no corresponde, o la cátedra no da un dato útil)
     * @param errors cuántos no se pudieron reconciliar por una falla (quedan para la próxima)
     */
    record Completed(int reviewed, int changed, int waiting, int errors, Ending ending) implements ReconciliationReport {
        public Completed {
            Objects.requireNonNull(ending, "Falta cómo terminó");
        }
    }
}
