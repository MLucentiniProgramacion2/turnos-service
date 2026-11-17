package ar.edu.um.programacion2.turnos.config;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.turnos.IntegrationTest;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

/**
 * Liquibase 5 pide {@code https://config.liquibase.com/analytics.yaml} al arrancar si no se le dice que no (se veía en el
 * log de {@code dev} de cada arranque, con el DEBUG de {@code HttpURLConnection}). El {@code SpringLiquibase} lo arma
 * {@link LiquibaseConfiguration} de JHipster, que no copiaba {@code spring.liquibase.analytics-enabled}.
 * <p>
 * El contexto se levanta con ese DEBUG prendido: si Liquibase hiciera el pedido, quedaría en la salida.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "logging.level.sun.net.www.protocol.http.HttpURLConnection=DEBUG")
class LiquibaseAnalyticsIT {

    @Autowired
    private SpringLiquibase liquibase;

    @Test
    void liquibaseArrancaSinPedirLaConfiguracionDeAnalytics(CapturedOutput salida) {
        assertThat(liquibase.getAnalyticsEnabled()).isFalse();
        assertThat(salida.getAll()).doesNotContain("analytics.yaml").doesNotContain("liquibase.com");
    }
}
