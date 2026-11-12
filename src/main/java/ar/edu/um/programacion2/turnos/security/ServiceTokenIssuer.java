package ar.edu.um.programacion2.turnos.security;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Emite el token técnico de servicio con el que turnos llama al catálogo (DPT-16, ADR 0005; las reglas del lado del
 * catálogo están en su ADR 0007):
 * <ul>
 * <li>{@code sub} = {@code iss} = {@value #ISSUER} y {@code aud} = [{@value #AUDIENCE}];</li>
 * <li>{@code auth} = {@code ROLE_SERVICE} y ningún rol de usuario;</li>
 * <li>{@code iat} y {@code exp} siempre, con la vida de {@link ServiceTokenProperties} (como máximo 5 min).</li>
 * </ul>
 * Lo firma el mismo {@code JwtEncoder} que los tokens de usuario (HS512, ADR 0003). Turnos rechaza estos tokens en sus
 * propios endpoints ({@link TokenAuthorizations}).
 * <p>
 * <strong>Uno nuevo por pedido, sin caché:</strong> firmar cuesta unos 12 µs (medido en el #10), contra cientos de
 * milisegundos de red. Un caché sumaría un margen de renovación, concurrencia y la regla de {@code iat} futuro, sin
 * ganancia medible. Nunca se reenvía el JWT del usuario.
 * <p>
 * Está en {@code security} y no en el adaptador del catálogo: usa el {@code JwtEncoder} y las constantes de los tokens,
 * y queda junto a la regla que rechaza estos tokens. Las features pueden usar {@code security} (TechnicalStructureTest).
 */
@Component
public class ServiceTokenIssuer {

    /** Quién emite el token: este servicio. El catálogo exige este {@code iss}. */
    public static final String ISSUER = "turnos-service";

    /** Para quién es: el catálogo exige que {@code aud} lo contenga. */
    public static final String AUDIENCE = "catalogo-service";

    private final JwtEncoder jwtEncoder;
    private final ServiceTokenProperties properties;
    private final Clock clock;

    @Autowired
    public ServiceTokenIssuer(JwtEncoder jwtEncoder, ServiceTokenProperties properties) {
        this(jwtEncoder, properties, Clock.systemUTC());
    }

    ServiceTokenIssuer(JwtEncoder jwtEncoder, ServiceTokenProperties properties, Clock clock) {
        this.jwtEncoder = Objects.requireNonNull(jwtEncoder, "Falta el JwtEncoder");
        this.properties = Objects.requireNonNull(properties, "Falta la configuración del token de servicio");
        this.clock = Objects.requireNonNull(clock, "Falta el reloj");
    }

    /** Un token nuevo. Nunca se loguea. */
    public String issue() {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .subject(ISSUER)
            .issuer(ISSUER)
            .audience(List.of(AUDIENCE))
            .issuedAt(now)
            .expiresAt(now.plus(properties.getLifetime()))
            .claim(SecurityUtils.AUTHORITIES_CLAIM, AuthoritiesConstants.SERVICE)
            .build();
        JwsHeader header = JwsHeader.with(SecurityUtils.JWT_ALGORITHM).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
