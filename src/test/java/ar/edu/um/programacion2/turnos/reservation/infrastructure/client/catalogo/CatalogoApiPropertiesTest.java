package ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Sin la URL del catálogo la aplicación no arranca; los timeouts por defecto son los de DPT-13. */
class CatalogoApiPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(CatalogoClientConfiguration.class);

    @Test
    void conUrlArrancaConLosTimeoutsDeDpt13() {
        runner
            .withPropertyValues("catalogo.api.url=http://localhost:8081")
            .run(context -> {
                assertThat(context).hasNotFailed();
                CatalogoApiProperties properties = context.getBean(CatalogoApiProperties.class);
                assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
                assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(5));
            });
    }

    @Test
    void sinUrlNoArranca() {
        runner.run(context -> assertThat(context.getStartupFailure()).isNotNull().hasStackTraceContaining("catalogo.api"));
    }

    /** Es lo que hace {@code application.yml} cuando falta {@code CATALOGO_API_URL}: {@code ${CATALOGO_API_URL:}}. */
    @Test
    void conLaVariableDeEntornoAusenteNoArranca() {
        runner
            .withPropertyValues("catalogo.api.url=${CATALOGO_API_URL_QUE_NO_EXISTE:}")
            .run(context -> assertThat(context.getStartupFailure()).isNotNull().hasStackTraceContaining("catalogo.api"));
    }
}
