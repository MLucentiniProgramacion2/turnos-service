package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Lo que devolvió la cátedra al crear el hold (contrato §9): sus identificadores y el vencimiento. Con estos IDs se
 * confirma, se reconcilia y se buscan los eventos de Kafka. {@code expiresAt} lo manda la cátedra: conservar el hold
 * localmente no lo extiende, y cada evento que trae uno nuevo lo reemplaza.
 */
public record CatedraHold(String holdId, String reservationProcessId, Instant expiresAt) {
    public CatedraHold {
        Texts.requireText(holdId, "el holdId");
        Texts.requireText(reservationProcessId, "el reservationProcessId");
        Objects.requireNonNull(expiresAt, "Falta el vencimiento del hold");
    }

    CatedraHold withExpiresAt(Instant newExpiresAt) {
        return newExpiresAt == null ? this : new CatedraHold(holdId, reservationProcessId, newExpiresAt);
    }
}
