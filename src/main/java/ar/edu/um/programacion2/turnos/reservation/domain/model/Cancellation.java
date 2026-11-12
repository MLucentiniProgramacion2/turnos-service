package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.time.Instant;
import java.util.Objects;

/** La cancelación: el motivo es opcional y admite hasta 500 caracteres, como en la cátedra (contrato §12 y §15.10). */
public record Cancellation(String reason, Instant cancelledAt) {
    public static final int MAX_REASON_LENGTH = 500;

    public Cancellation {
        Texts.optionalText(reason, "el motivo de la cancelación", MAX_REASON_LENGTH);
        Objects.requireNonNull(cancelledAt, "Falta el instante de cancelación");
    }
}
