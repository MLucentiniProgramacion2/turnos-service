package ar.edu.um.programacion2.turnos.reservation.domain.ports.out;

import ar.edu.um.programacion2.turnos.reservation.domain.model.ProfessionalAgenda;
import java.util.Optional;

/**
 * La agenda vigente del servicio de catálogo (CU-07 del catálogo), para CU-03 y CU-04. Sin reintentos (DPT-13).
 */
public interface ProfessionalAgendaProvider {
    /**
     * @param requestedBy el login del usuario, solo para el log del catálogo; puede ser {@code null}
     * @return vacío si el catálogo no conoce al profesional ({@code 404 PROFESSIONAL_NOT_FOUND})
     * @throws CatalogException si el catálogo no está disponible, rechaza el token de servicio o responde fuera de contrato
     */
    Optional<ProfessionalAgenda> fetchAgenda(long professionalId, String requestedBy);
}
