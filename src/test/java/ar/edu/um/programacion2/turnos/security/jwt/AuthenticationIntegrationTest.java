package ar.edu.um.programacion2.turnos.security.jwt;

import ar.edu.um.programacion2.turnos.config.SecurityConfiguration;
import ar.edu.um.programacion2.turnos.config.SecurityJwtConfiguration;
import ar.edu.um.programacion2.turnos.config.WebConfigurer;
import ar.edu.um.programacion2.turnos.management.SecurityMetersService;
import ar.edu.um.programacion2.turnos.web.rest.AuthenticateController;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import tech.jhipster.config.JHipsterProperties;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(
    properties = {
        "jhipster.security.authentication.jwt.token-validity-in-seconds=60000",
    },
    classes = {
        JHipsterProperties.class,
        WebConfigurer.class,
        SecurityConfiguration.class,
        SecurityJwtConfiguration.class,
        SecurityMetersService.class,
        AuthenticateController.class,
        JwtAuthenticationTestUtils.class,
    }
)
public @interface AuthenticationIntegrationTest {}
