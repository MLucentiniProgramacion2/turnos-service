package ar.edu.um.programacion2.turnos.reservation.domain.ports.out;

import ar.edu.um.programacion2.turnos.reservation.domain.model.OccupancyReport;
import java.time.LocalDate;

/** Ocupaciones de la cátedra (contrato §8), para CU-03. Es una consulta sin efectos. */
public interface OccupancyProvider {
    /**
     * Un solo intento: los reintentos, si corresponden, son del caso de uso (DPT-08).
     *
     * @throws CatedraUnavailableException, CatedraRejectedException, CatedraAccessDeniedException o
     *     CatedraInvalidResponseException (nunca {@link CatedraOutcomeUnknownException}: no tiene efectos)
     */
    OccupancyReport fetchOccupancies(long professionalId, LocalDate from, LocalDate to);
}
