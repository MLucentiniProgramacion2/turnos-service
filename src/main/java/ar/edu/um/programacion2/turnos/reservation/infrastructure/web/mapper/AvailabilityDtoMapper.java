package ar.edu.um.programacion2.turnos.reservation.infrastructure.web.mapper;

import ar.edu.um.programacion2.turnos.reservation.domain.model.ProfessionalAgenda.AgendaProfessional;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ProfessionalAvailability;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.web.dto.AvailabilityResponse;
import org.springframework.stereotype.Component;

/** Traduce la disponibilidad del dominio al DTO de la respuesta: el modelo nunca sale por REST. */
@Component
public class AvailabilityDtoMapper {

    public AvailabilityResponse toResponse(ProfessionalAvailability availability) {
        AgendaProfessional professional = availability.professional();
        return new AvailabilityResponse(
            new AvailabilityResponse.Professional(
                professional.id(),
                professional.firstName(),
                professional.lastName(),
                new AvailabilityResponse.Category(professional.category().id(), professional.category().name(), professional.category().enabled())
            ),
            availability.from(),
            availability.to(),
            availability.zone().getId(),
            availability
                .days()
                .stream()
                .map(day ->
                    new AvailabilityResponse.Day(
                        day.date(),
                        day.slots().stream().map(slot -> new AvailabilityResponse.Slot(slot.startTime(), slot.endTime())).toList()
                    )
                )
                .toList()
        );
    }
}
