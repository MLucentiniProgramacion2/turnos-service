package ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.turnos.IntegrationTest;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ProfessionalAgendaProvider;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.client.FakeHttpServer;
import ar.edu.um.programacion2.turnos.security.SecurityUtils;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.util.Base64;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * El cliente de la agenda con los beans reales de Spring: el pedido al catálogo lleva el token técnico de servicio firmado
 * con la clave de turnos, y nunca el JWT de la cátedra (que está configurado en el mismo contexto, en otro
 * {@code RestClient}) ni el del usuario autenticado.
 */
@IntegrationTest
class CatalogoAgendaClientIT {

    private static final FakeHttpServer CATALOGO = iniciar();

    @DynamicPropertySource
    static void catalogo(DynamicPropertyRegistry registry) {
        registry.add("catalogo.api.url", () -> CATALOGO.baseUrl().toString());
    }

    @AfterAll
    static void detener() {
        CATALOGO.close();
    }

    @Autowired
    private ProfessionalAgendaProvider agendaProvider;

    @Value("${catedra.api.token}")
    private String tokenDeLaCatedra;

    @Value("${jhipster.security.authentication.jwt.base64-secret}")
    private String jwtKey;

    @Test
    void elPedidoLlevaElTokenDeServicioYNoElDeLaCatedraNiElDelUsuario() {
        CATALOGO.respondProblem(404, "PROFESSIONAL_NOT_FOUND");
        String tokenDelUsuario = autenticarUnUsuario();
        try {
            assertThat(agendaProvider.fetchAgenda(15, "ana.perez")).isEmpty();
        } finally {
            SecurityContextHolder.clearContext();
        }

        FakeHttpServer.ReceivedRequest pedido = CATALOGO.requests().getLast();
        String token = pedido.authorization().substring("Bearer ".length());
        assertThat(token).isNotEqualTo(tokenDeLaCatedra).isNotEqualTo(tokenDelUsuario);
        Jwt decodificado = decoder().decode(token);
        assertThat(decodificado.getClaimAsString("iss")).isEqualTo("turnos-service");
        assertThat(decodificado.getClaimAsString(SecurityUtils.AUTHORITIES_CLAIM)).isEqualTo("ROLE_SERVICE");
        assertThat(Duration.between(decodificado.getIssuedAt(), decodificado.getExpiresAt())).isEqualTo(Duration.ofMinutes(5));
        assertThat(pedido.userLogin()).isEqualTo("ana.perez");
    }

    /** Un usuario autenticado con un JWT de usuario real en el contexto de seguridad, como en un pedido de la app. */
    private String autenticarUnUsuario() {
        byte[] clave = Base64.from(jwtKey).decode();
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(clave, "HmacSHA512")));
        Instant ahora = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .subject("ana.perez")
            .issuedAt(ahora)
            .expiresAt(ahora.plusSeconds(300))
            .claim(SecurityUtils.AUTHORITIES_CLAIM, "ROLE_USER")
            .claim(SecurityUtils.USER_ID_CLAIM, 42L)
            .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SecurityUtils.JWT_ALGORITHM).build(), claims)).getTokenValue();
        Jwt jwt = decoder().decode(token);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        assertThat(SecurityUtils.getCurrentUserJWT()).contains(token);
        return token;
    }

    private NimbusJwtDecoder decoder() {
        byte[] clave = Base64.from(jwtKey).decode();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(clave, "HmacSHA512")).macAlgorithm(MacAlgorithm.HS512).build();
        decoder.setJwtValidator(jwt -> OAuth2TokenValidatorResult.success());
        return decoder;
    }

    private static FakeHttpServer iniciar() {
        try {
            return FakeHttpServer.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
