package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Las ocupaciones de un profesional en un rango de fechas (contrato §8). Una lista vacía es una respuesta válida. */
public record OccupancyReport(long professionalId, LocalDate from, LocalDate to, List<Occupancy> occupancies) {
    public OccupancyReport {
        Objects.requireNonNull(from, "Falta la fecha desde");
        Objects.requireNonNull(to, "Falta la fecha hasta");
        occupancies = List.copyOf(Objects.requireNonNull(occupancies, "Faltan las ocupaciones"));
    }
}
