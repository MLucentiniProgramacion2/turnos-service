package ar.edu.um.programacion2.turnos.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * El token técnico de servicio que emite turnos cumple las reglas con que lo autoriza el catálogo
 * ({@code ServiceTokenAuthorizationManager}, ADR 0007 del catálogo). Las reglas están copiadas acá
 * ({@link #cumpleLasReglasDelCatalogo}): si alguno de los dos lados cambia, este test es el que lo avisa.
 */
class ServiceTokenIssuerTest {

    /** Con milisegundos: Nimbus trunca {@code iat} y {@code exp} a segundos. */
    private static final Instant AHORA = Instant.parse("2026-11-11T12:00:00.987Z");

    private final SecretKey clave = claveAleatoria();
    private final NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(clave).macAlgorithm(MacAlgorithm.HS512).build();

    @Test
    void conLaVidaMaximaDe5MinutosCumpleExactamenteLaReglaDelCatalogo() {
        Jwt token = decodificar(emisor(Duration.ofMinutes(5)).issue());

        assertThat(Duration.between(token.getIssuedAt(), token.getExpiresAt())).isEqualTo(Duration.ofMinutes(5));
        assertThat(token.getIssuedAt()).isEqualTo(Instant.parse("2026-11-11T12:00:00Z"));
        assertThat(cumpleLasReglasDelCatalogo(token, AHORA)).isTrue();
    }

    @Test
    void llevaLosClaimsDelContratoYNadaDeUnUsuario() {
        Jwt token = decodificar(emisor(Duration.ofMinutes(2)).issue());

        assertThat(token.getSubject()).isEqualTo("turnos-service");
        assertThat(token.getClaimAsString(JwtClaimNames.ISS)).isEqualTo("turnos-service");
        assertThat(token.getAudience()).containsExactly("catalogo-service");
        assertThat(token.getClaimAsString(SecurityUtils.AUTHORITIES_CLAIM)).isEqualTo("ROLE_SERVICE");
        assertThat(token.getClaims()).containsOnlyKeys("sub", "iss", "aud", "iat", "exp", SecurityUtils.AUTHORITIES_CLAIM);
        assertThat(cumpleLasReglasDelCatalogo(token, AHORA)).isTrue();
    }

    /** Si la vida pasara de 5 min (la validación del arranque lo impide), el catálogo lo rechazaría: la regla funciona. */
    @Test
    void conMasDe5MinutosElCatalogoLoRechazaria() {
        Jwt token = decodificar(emisor(Duration.ofMinutes(5).plusSeconds(1)).issue());

        assertThat(cumpleLasReglasDelCatalogo(token, AHORA)).isFalse();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // La vida configurable: no arranca fuera de [30 s, 5 min]
    // ---------------------------------------------------------------------------------------------------------------

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(ServiceTokenConfiguration.class);

    @Test
    void porDefectoLaVidaEsDe5Minutos() {
        runner.run(context ->
            assertThat(context.getBean(ServiceTokenProperties.class).getLifetime()).isEqualTo(Duration.ofMinutes(5))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = { "5m", "300s", "30s", "2m" })
    void arrancaConUnaVidaDe30SegundosA5Minutos(String vida) {
        runner.withPropertyValues("security.service-token.lifetime=" + vida).run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = { "6m", "301s", "29s", "0s", "-1s" })
    void noArrancaConUnaVidaFueraDeRango(String vida) {
        runner
            .withPropertyValues("security.service-token.lifetime=" + vida)
            .run(context ->
                assertThat(context.getStartupFailure()).isNotNull().hasStackTraceContaining("security.service-token.lifetime")
            );
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Ayudas
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Las reglas de {@code ServiceTokenAuthorizationManager} del catálogo, sobre un token ya validado por el decoder:
     * {@code ROLE_SERVICE} sin roles de usuario, {@code aud} con {@code catalogo-service}, {@code iss} igual a
     * {@code turnos-service} (leído como texto: {@code getIssuer()} falla porque no es una URL), {@code iat} y {@code exp}
     * presentes, {@code exp − iat} de hasta 5 min e {@code iat} no más de 60 s en el futuro.
     */
    static boolean cumpleLasReglasDelCatalogo(Jwt jwt, Instant ahora) {
        List<String> roles = List.of(jwt.getClaimAsString(SecurityUtils.AUTHORITIES_CLAIM).split(" "));
        if (!roles.contains("ROLE_SERVICE") || roles.contains("ROLE_USER") || roles.contains("ROLE_ADMIN")) {
            return false;
        }
        if (jwt.getAudience() == null || !jwt.getAudience().contains("catalogo-service")) {
            return false;
        }
        if (!"turnos-service".equals(jwt.getClaimAsString(JwtClaimNames.ISS))) {
            return false;
        }
        Instant iat = jwt.getIssuedAt();
        Instant exp = jwt.getExpiresAt();
        if (iat == null || exp == null) {
            return false;
        }
        return !iat.isAfter(ahora.plusSeconds(60)) && Duration.between(iat, exp).compareTo(Duration.ofMinutes(5)) <= 0;
    }

    private ServiceTokenIssuer emisor(Duration vida) {
        ServiceTokenProperties properties = new ServiceTokenProperties();
        properties.setLifetime(vida);
        return new ServiceTokenIssuer(new NimbusJwtEncoder(new ImmutableSecret<>(clave)), properties, Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    /** Valida la firma; la vigencia no (el reloj del test es fijo y el del decoder, real). */
    private Jwt decodificar(String token) {
        decoder.setJwtValidator(jwt -> org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success());
        return decoder.decode(token);
    }

    private static SecretKey claveAleatoria() {
        byte[] bytes = new byte[64];
        new SecureRandom().nextBytes(bytes);
        return new SecretKeySpec(bytes, "HmacSHA512");
    }
}
