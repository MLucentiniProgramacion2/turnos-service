package ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catedra;

import static ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catedra.CatedraFormat.date;
import static ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catedra.CatedraFormat.instant;
import static ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catedra.CatedraFormat.required;

import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraHold;
import ar.edu.um.programacion2.turnos.reservation.domain.model.HoldConfirmation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.Occupancy;
import ar.edu.um.programacion2.turnos.reservation.domain.model.OccupancyReport;
import ar.edu.um.programacion2.turnos.reservation.domain.model.OccupancyStatus;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.client.LocalTimeFormat;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Traduce los JSON de la cátedra al dominio, validando lo que el contrato garantiza. Lanza
 * {@link IllegalArgumentException} con el campo que falla (sin su valor); el cliente decide qué excepción del puerto
 * corresponde: "respuesta inválida" en una consulta, "resultado desconocido" en una operación con efectos.
 */
final class CatedraMapper {

    private CatedraMapper() {}

    /**
     * Ocupaciones (contrato §8). Tienen que ser del profesional y del rango pedidos, y cada una dentro del rango. Un
     * {@code status} distinto de {@code HELD} o {@code CONFIRMED} es inválido: no se puede decidir si el turno está libre.
     */
    static OccupancyReport toReport(CatedraDtos.OccupanciesResponse response, long professionalId, LocalDate from, LocalDate to) {
        required(response, "el cuerpo");
        if (!Objects.equals(required(response.professionalId(), "professionalId"), professionalId)) {
            throw new IllegalArgumentException("professionalId no es el pedido");
        }
        if (!date(response.from(), "from").equals(from) || !date(response.to(), "to").equals(to)) {
            throw new IllegalArgumentException("from o to no son los pedidos");
        }
        List<Occupancy> occupancies = required(response.occupancies(), "occupancies")
            .stream()
            .map(item -> toOccupancy(item, from, to))
            .toList();
        return new OccupancyReport(professionalId, from, to, occupancies);
    }

    private static Occupancy toOccupancy(CatedraDtos.OccupancyItem item, LocalDate from, LocalDate to) {
        required(item, "una ocupación");
        LocalDate date = date(item.date(), "occupancies.date");
        if (date.isBefore(from) || date.isAfter(to)) {
            throw new IllegalArgumentException("occupancies.date está fuera del rango pedido");
        }
        OccupancyStatus status = switch (required(item.status(), "occupancies.status")) {
            case "HELD" -> OccupancyStatus.HELD;
            case "CONFIRMED" -> OccupancyStatus.CONFIRMED;
            default -> throw new IllegalArgumentException("occupancies.status no es HELD ni CONFIRMED");
        };
        return new Occupancy(
            date,
            LocalTimeFormat.parse(item.startTime(), "occupancies.startTime"),
            status,
            item.expiresAt() == null ? null : instant(item.expiresAt(), "occupancies.expiresAt")
        );
    }

    /** {@code 201} del hold (contrato §9): {@code status} tiene que ser {@code HELD}. */
    static CatedraHold toHold(CatedraDtos.HoldResponse response) {
        required(response, "el cuerpo");
        if (!"HELD".equals(response.status())) {
            throw new IllegalArgumentException("status no es HELD");
        }
        return new CatedraHold(
            required(response.holdId(), "holdId"),
            required(response.reservationProcessId(), "reservationProcessId"),
            instant(response.expiresAt(), "expiresAt")
        );
    }

    /** {@code 202} del confirm (contrato §10): {@code status} tiene que ser {@code WAITING_FOR_PHONE}. */
    static HoldConfirmation toConfirmation(CatedraDtos.ConfirmResponse response) {
        required(response, "el cuerpo");
        if (!"WAITING_FOR_PHONE".equals(response.status())) {
            throw new IllegalArgumentException("status no es WAITING_FOR_PHONE");
        }
        return new HoldConfirmation(
            required(response.reservationProcessId(), "reservationProcessId"),
            instant(response.expiresAt(), "expiresAt")
        );
    }
}
