package ar.edu.um.programacion2.turnos.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Un {@link ServiceTokenIssuer} determinista para los tests de otros paquetes (reloj y clave fijos: siempre firma el
 * mismo token), y la comprobación de que un token cumple las reglas del catálogo.
 */
public final class ServiceTokenIssuerTestSupport {

    private static final Instant AHORA = Instant.parse("2026-11-11T12:00:00Z");
    private static final SecretKey CLAVE = new SecretKeySpec(
        "clave-de-test-del-token-de-servicio-solo-para-tests-0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8),
        "HmacSHA512"
    );

    private ServiceTokenIssuerTestSupport() {}

    public static ServiceTokenIssuer emisorFijo() {
        return new ServiceTokenIssuer(new NimbusJwtEncoder(new ImmutableSecret<>(CLAVE)), new ServiceTokenProperties(), Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    /** Si el token está firmado con la clave de {@link #emisorFijo()} y cumple las reglas del catálogo. */
    public static boolean esTokenDeServicio(String token) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(CLAVE).macAlgorithm(MacAlgorithm.HS512).build();
        decoder.setJwtValidator(jwt -> OAuth2TokenValidatorResult.success());
        return ServiceTokenIssuerTest.cumpleLasReglasDelCatalogo(decoder.decode(token), AHORA);
    }
}
