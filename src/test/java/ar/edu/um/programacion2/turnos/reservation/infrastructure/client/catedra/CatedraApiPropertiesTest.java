package ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catedra;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

/**
 * Sin URL o sin token de la cátedra la aplicación no arranca (como con {@code JWT_BASE64_SECRET}, ADR 0003), y el token
 * no se imprime. Levanta solo {@link CatedraClientConfiguration}, sin base de datos.
 */
class CatedraApiPropertiesTest {

    private static final String URL = "catedra.api.url=http://catedra.test:8080";
    private static final String TOKEN = "catedra.api.token=token-de-prueba-1a2b3c";

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(CatedraClientConfiguration.class);

    @Test
    void conUrlYTokenArrancaConLosTimeoutsPorDefecto() {
        runner
            .withPropertyValues(URL, TOKEN)
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(RestClient.class);
                CatedraApiProperties properties = context.getBean(CatedraApiProperties.class);
                assertThat(properties.getUrl()).isEqualTo(URI.create("http://catedra.test:8080"));
                assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
                assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(5));
            });
    }

    @Test
    void tomaLosTimeoutsConfigurados() {
        runner
            .withPropertyValues(URL, TOKEN, "catedra.api.connect-timeout=1s", "catedra.api.read-timeout=1m", "catedra.api.list-deadline=2m")
            .run(context -> {
                CatedraApiProperties properties = context.getBean(CatedraApiProperties.class);
                assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(1));
                assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofMinutes(1));
            });
    }

    @Test
    void sinUrlNoArranca() {
        runner.withPropertyValues(TOKEN).run(context -> assertFailsOn(context.getStartupFailure(), "url"));
    }

    @Test
    void conUrlVaciaNoArranca() {
        runner.withPropertyValues("catedra.api.url=", TOKEN).run(context -> assertFailsOn(context.getStartupFailure(), "url"));
    }

    @Test
    void sinTokenNoArranca() {
        runner.withPropertyValues(URL).run(context -> assertFailsOn(context.getStartupFailure(), "token"));
    }

    @Test
    void conTokenEnBlancoNoArranca() {
        runner.withPropertyValues(URL, "catedra.api.token=   ").run(context -> assertFailsOn(context.getStartupFailure(), "token"));
    }

    /** Es lo que hace {@code application.yml} cuando falta {@code CATEDRA_JWT}: {@code ${CATEDRA_JWT:}}. */
    @Test
    void conLaVariableDeEntornoAusenteNoArranca() {
        runner
            .withPropertyValues(URL, "catedra.api.token=${CATEDRA_JWT_QUE_NO_EXISTE:}")
            .run(context -> assertFailsOn(context.getStartupFailure(), "token"));
    }

    /** Por qué el {@code :} vacío: sin él, el placeholder sin resolver quedaría como texto y pasaría la validación. */
    @Test
    void sinValorVacioElPlaceholderSinResolverQuedaComoTextoLiteral() {
        runner
            .withPropertyValues(URL, "catedra.api.token=${CATEDRA_JWT_QUE_NO_EXISTE}")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(CatedraApiProperties.class).getToken()).isEqualTo("${CATEDRA_JWT_QUE_NO_EXISTE}");
            });
    }

    @Test
    void toStringNoMuestraElToken() {
        CatedraApiProperties properties = new CatedraApiProperties();
        properties.setUrl(URI.create("http://catedra.test:8080"));
        properties.setToken("token-de-prueba-1a2b3c");

        assertThat(properties.toString()).doesNotContain("token-de-prueba-1a2b3c").contains("[oculto]").contains("catedra.test");
    }

    @Test
    void elListadoTienePaginasDe200HastaCincoYUnPlazoDe10Segundos() {
        runner
            .withPropertyValues(URL, TOKEN)
            .run(context -> {
                CatedraApiProperties properties = context.getBean(CatedraApiProperties.class);
                assertThat(properties.getListPageSize()).isEqualTo(200);
                assertThat(properties.getListMaxPages()).isEqualTo(5);
                assertThat(properties.getListDeadline()).isEqualTo(Duration.ofSeconds(10));
                assertThat(properties.requestWorstCase()).isEqualTo(Duration.ofSeconds(7));
            });
    }

    @ParameterizedTest
    @ValueSource(strings = { "catedra.api.list-page-size=0", "catedra.api.list-page-size=2001", "catedra.api.list-max-pages=0" })
    void unTamanoDePaginaQueLaCatedraRecortariaOUnTopeEnCeroNoArrancan(String propiedad) {
        // Con más de 2000, la cátedra recorta sin avisar (medido el 16/11).
        runner.withPropertyValues(URL, TOKEN, propiedad).run(context -> assertFailsOn(context.getStartupFailure(), "list"));
    }

    @Test
    void unPlazoDeListadoMenorQueUnPedidoNoArranca() {
        // 2 s + 5 s = 7 s: la primera página siempre se pide, así que con 6 s el plazo no sería un techo.
        runner
            .withPropertyValues(URL, TOKEN, "catedra.api.list-deadline=6s")
            .run(context -> assertFailsOn(context.getStartupFailure(), "list-deadline"));
    }

    private static void assertFailsOn(Throwable failure, String field) {
        assertThat(failure).as("la aplicación tenía que no arrancar").isNotNull();
        assertThat(failure).hasStackTraceContaining("catedra.api").hasStackTraceContaining(field);
    }
}
