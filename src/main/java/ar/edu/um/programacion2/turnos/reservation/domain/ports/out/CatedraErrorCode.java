package ar.edu.um.programacion2.turnos.reservation.domain.ports.out;

import java.util.Arrays;

/**
 * Los {@code code} de la matriz de errores de la cátedra (contrato §13.1) que pueden devolver las operaciones que usa
 * turnos: ocupaciones, holds, listado y cancelación. Los de registro y login de la cuenta técnica no están: turnos no los
 * llama. Es un enum para que los casos de uso decidan con un {@code switch} completo; un {@code code} que no está acá (o
 * que no vino) es {@link #UNRECOGNIZED}. Se decide por {@code status} y {@code code}, nunca por el texto de {@code detail}.
 */
public enum CatedraErrorCode {
    VALIDATION_ERROR,
    STUDENT_INTEGRATION_NOT_FOUND,
    INVALID_DATE_RANGE,
    PROFESSIONAL_NOT_FOUND,
    PROFESSIONAL_DISABLED,
    INVALID_SLOT,
    SLOT_ALREADY_RESERVED,
    SLOT_ALREADY_HELD,
    HOLD_NOT_FOUND,
    HOLD_EXPIRED,
    HOLD_OWNER_MISMATCH,
    EXTERNAL_PATIENT_MISMATCH,
    RESERVATION_PROCESS_ALREADY_CONFIRMED,
    RESERVATION_NOT_FOUND,
    RESERVATION_OWNER_MISMATCH,
    RESERVATION_NOT_CANCELLABLE,
    /** Un {@code code} que el contrato no lista para estas operaciones, o ninguno. */
    UNRECOGNIZED;

    /** El {@code code} recibido como texto; {@code null} o desconocido da {@link #UNRECOGNIZED}. */
    public static CatedraErrorCode from(String code) {
        return Arrays.stream(values())
            .filter(value -> value != UNRECOGNIZED && value.name().equals(code))
            .findFirst()
            .orElse(UNRECOGNIZED);
    }
}
