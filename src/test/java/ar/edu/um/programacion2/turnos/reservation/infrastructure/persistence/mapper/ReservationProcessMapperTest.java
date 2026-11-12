package ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessMatrix;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.entity.ReservationProcessEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ReservationProcessMapperTest {

    private final ReservationProcessMapper mapper = new ReservationProcessMapper();

    @ParameterizedTest
    @EnumSource(ReservationProcessStatus.class)
    void idaYVueltaConservaTodosLosDatos(ReservationProcessStatus estado) {
        ReservationProcess original = ReservationProcessMatrix.procesoEn(estado, 7);

        ReservationProcess vuelta = mapper.toDomain(mapper.toEntity(original));

        assertThat(vuelta).usingRecursiveComparison().isEqualTo(original);
    }

    @Test
    void unaFilaIncoherenteNoSeLee() {
        ReservationProcessEntity fila = mapper.toEntity(ReservationProcessMatrix.procesoEn(ReservationProcessStatus.CONFIRMED, 7));
        fila.setReservationId(null);
        fila.setConfirmedAt(null);

        assertThatThrownBy(() -> mapper.toDomain(fila)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CONFIRMED");
    }

    @Test
    void elToStringDeLaEntidadNoTieneDatosPersonales() {
        ReservationProcessEntity fila = mapper.toEntity(ReservationProcessMatrix.procesoEn(ReservationProcessStatus.PHONE_SUBMITTED, 7));

        assertThat(fila.toString()).doesNotContain("Juan", "Perez", "5492615551234").contains("PHONE_SUBMITTED");
    }
}
