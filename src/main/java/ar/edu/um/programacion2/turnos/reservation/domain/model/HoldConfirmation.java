package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Respuesta {@code 202} del confirm (contrato §10): la cátedra creó la reserva {@code PHONE_PENDING} y va a publicar
 * {@code AdditionalInformationRequested}. El proceso tiene que completarse antes de {@code expiresAt}.
 */
public record HoldConfirmation(String reservationProcessId, Instant expiresAt) {
    public HoldConfirmation {
        Texts.requireText(reservationProcessId, "el reservationProcessId");
        Objects.requireNonNull(expiresAt, "Falta el vencimiento");
    }
}
