package ar.edu.um.programacion2.turnos.reservation.domain.ports.out;

import java.util.Objects;

/**
 * La cátedra respondió y rechazó la operación con un {@code code} funcional (contrato §13.1), por ejemplo
 * {@code 409 SLOT_ALREADY_HELD} o {@code 404 HOLD_EXPIRED}. Qué significa lo decide el caso de uso con {@link #status()} y
 * {@link #code()}; {@code 409 RESERVATION_PROCESS_ALREADY_CONFIRMED}, por ejemplo, es un éxito para el confirm (CU-04 11d).
 */
public final class CatedraRejectedException extends CatedraException {

    private final int status;
    private final CatedraErrorCode code;

    public CatedraRejectedException(int status, CatedraErrorCode code, String message) {
        super(message);
        this.status = status;
        this.code = Objects.requireNonNull(code, "Falta el code");
    }

    public int status() {
        return status;
    }

    public CatedraErrorCode code() {
        return code;
    }
}
