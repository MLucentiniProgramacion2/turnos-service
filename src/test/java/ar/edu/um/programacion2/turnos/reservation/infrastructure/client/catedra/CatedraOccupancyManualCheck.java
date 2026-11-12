package ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catedra;

import ar.edu.um.programacion2.turnos.reservation.domain.model.OccupancyReport;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraException;
import java.io.IOException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Comprobación manual: ocupaciones reales de la cátedra. <strong>Nunca corre en el CI</strong>: el nombre no coincide con
 * los patrones de surefire ({@code *Test}) ni de failsafe ({@code *IT*}) y, además, exige {@code -Dcatedra.manual=true}.
 * No tiene efectos (es un {@code GET}).
 * <p>
 * Con ZeroTier conectado ({@code sudo zerotier-cli listnetworks}) y {@code CATEDRA_API_URL} y {@code CATEDRA_JWT} en el
 * {@code .env} (una variable de entorno con el mismo nombre tiene prioridad):
 *
 * <pre>./mvnw test -Dtest=CatedraOccupancyManualCheck -Dcatedra.manual=true [-Dprofesional=202 -Ddesde=2026-12-14 -Dhasta=2026-12-18]</pre>
 *
 * Imprime una línea por ocupación (fecha, hora, estado y vencimiento). Nunca imprime el token ni la URL.
 */
@EnabledIfSystemProperty(named = "catedra.manual", matches = "true")
class CatedraOccupancyManualCheck {

    @Test
    void pideLasOcupacionesALaCatedraReal() throws IOException {
        long profesional = Long.parseLong(System.getProperty("profesional", "202"));
        LocalDate desde = LocalDate.parse(System.getProperty("desde", "2026-12-14"));
        LocalDate hasta = LocalDate.parse(System.getProperty("hasta", "2026-12-18"));
        CatedraOccupancyClient client = new CatedraOccupancyClient(CatedraManualCheckSupport.restClient());

        OccupancyReport report;
        try {
            report = client.fetchOccupancies(profesional, desde, hasta);
        } catch (CatedraException e) {
            throw CatedraManualCheckSupport.oneLineFailure(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        System.out.println("profesional=" + profesional + " desde=" + desde + " hasta=" + hasta + " ocupaciones=" + report.occupancies().size());
        report
            .occupancies()
            .forEach(o -> System.out.println("  " + o.date() + " " + o.startTime() + " " + o.status() + " expiresAt=" + o.expiresAt()));
    }
}
