package ar.edu.um.programacion2.turnos.config;

import static org.springframework.security.config.Customizer.withDefaults;

import ar.edu.um.programacion2.turnos.security.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.SecurityFilterChain;
import tech.jhipster.config.JHipsterProperties;

@Configuration
@EnableMethodSecurity(securedEnabled = true)
public class SecurityConfiguration {

    private final JHipsterProperties jHipsterProperties;

    public SecurityConfiguration(JHipsterProperties jHipsterProperties) {
        this.jHipsterProperties = jHipsterProperties;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) {
        http.cors(withDefaults())
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(authz ->
                // prettier-ignore
                authz
                    .requestMatchers(HttpMethod.POST, "/api/authenticate").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/authenticate").permitAll()
                    .requestMatchers("/api/register").permitAll()
                    // Las reglas de usuario rechazan el token técnico de servicio (ROLE_SERVICE, ADR 0005): turnos lo
                    // firma con la misma clave que los de usuario, así que la firma no dice qué clase de token es.
                    .requestMatchers("/api/admin/**").access(TokenAuthorizations.userToken(AuthoritiesConstants.ADMIN))
                    .requestMatchers("/api/**").access(TokenAuthorizations.userToken(AuthoritiesConstants.USER, AuthoritiesConstants.ADMIN))
                    .requestMatchers("/v3/api-docs/**").access(TokenAuthorizations.userToken(AuthoritiesConstants.ADMIN))
                    .requestMatchers("/management/health").permitAll()
                    .requestMatchers("/management/health/**").permitAll()
                    // Pública: responde lo mismo con cualquier token que sin token.
                    .requestMatchers("/management/info").permitAll()
                    .requestMatchers("/management/**").access(TokenAuthorizations.userToken(AuthoritiesConstants.ADMIN))
            )
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions ->
                exceptions
                    .authenticationEntryPoint(new BearerTokenAuthenticationEntryPoint())
                    .accessDeniedHandler(new BearerTokenAccessDeniedHandler())
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(withDefaults()));
        return http.build();
    }
}
