package ar.edu.um.programacion2.turnos.reservation.domain.ports.out;

import ar.edu.um.programacion2.turnos.reservation.domain.model.AppointmentSlot;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraHold;
import ar.edu.um.programacion2.turnos.reservation.domain.model.HoldConfirmation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PatientName;

/**
 * Crear y confirmar holds en la cátedra (contrato §9 y §10), para CU-04 y CU-11. Son operaciones <strong>con
 * efectos</strong>: un solo intento, y una falla después de conectar es {@link CatedraOutcomeUnknownException}, nunca
 * "falló". El POST del hold no se repite nunca (DPT-06); el confirm, solo después de reconciliar (DPT-07).
 */
public interface HoldGateway {
    /** {@code POST /api/appointment-holds}. La hora se manda como {@code HH:mm}. */
    CatedraHold createHold(AppointmentSlot slot, String externalPatientId);

    /** {@code POST /api/appointment-holds/{holdId}/confirm}. */
    HoldConfirmation confirmHold(String holdId, String externalPatientId, PatientName patient);
}
