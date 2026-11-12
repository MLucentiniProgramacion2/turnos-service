package ar.edu.um.programacion2.turnos.aop.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Optional;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class LoggingAspectTest {

    private static final String DECLARING_TYPE = "ar.edu.um.programacion2.turnos.service.LoggedService";
    private static final String METHOD = "loggedMethod";

    private final Environment env = mock(Environment.class);
    private final ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private LoggingAspect aspect;
    private Logger logger;

    @BeforeEach
    void setup() {
        Signature signature = mock(Signature.class);
        when(signature.getDeclaringTypeName()).thenReturn(DECLARING_TYPE);
        when(signature.getName()).thenReturn(METHOD);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[] { "arg" });
        when(env.acceptsProfiles(any(Profiles.class))).thenReturn(true);

        logger = (Logger) LoggerFactory.getLogger(DECLARING_TYPE);
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);

        aspect = new LoggingAspect(env);
    }

    @AfterEach
    void teardown() {
        logger.detachAppender(appender);
        logger.setLevel(null);
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void logAroundLogsEnterAndExitWithTypesOnly() throws Throwable {
        when(joinPoint.proceed()).thenReturn("result");

        Object result = aspect.logAround(joinPoint);

        assertThat(result).isEqualTo("result");
        assertThat(messages()).containsExactly(
            "Enter: loggedMethod() with argument types = [String]",
            "Exit: loggedMethod() with result type = String"
        );
    }

    @Test
    void logAroundRethrowsIllegalArgumentExceptionAndLogsTypesOnly() throws Throwable {
        when(joinPoint.getArgs()).thenReturn(new Object[] { "ClaveSecreta123" });
        when(joinPoint.proceed()).thenThrow(new IllegalArgumentException("invalid"));

        assertThatThrownBy(() -> aspect.logAround(joinPoint))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("invalid");

        assertThat(messages()).contains("Illegal argument types: [String] in loggedMethod()");
        assertThat(String.join("\n", messages())).doesNotContain("ClaveSecreta123");
    }

    /**
     * El caso de {@code AuthenticateController.authorize()} (issue #10): el {@code toString()} de un {@code ResponseEntity}
     * incluye sus cabeceras, así que {@code Exit:} imprimía el JWT del usuario en el log de {@code dev}.
     */
    @Test
    void exitDoesNotLogTheTokenOfAResponseEntityWithAuthorization() throws Throwable {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJzb25kYSJ9.firma-falsa");
        when(joinPoint.proceed()).thenReturn(new ResponseEntity<>("cuerpo", headers, HttpStatus.OK));

        aspect.logAround(joinPoint);

        assertThat(messages()).contains("Exit: loggedMethod() with result type = ResponseEntity");
        assertThat(String.join("\n", messages())).doesNotContain("eyJ").doesNotContain("Bearer").doesNotContain("cuerpo");
    }

    /**
     * El caso de {@code UserService.registerUser(dto, password)} y {@code changePassword(actual, nueva)} (issue #10):
     * {@code Enter:} imprimía las contraseñas en texto plano en el log de {@code dev}.
     */
    @Test
    void enterDoesNotLogPasswordsOrPersonalDataPassedAsArguments() throws Throwable {
        when(joinPoint.getArgs()).thenReturn(new Object[] { "ClaveActual123", "ClaveNueva456", Optional.of("ana@example.com"), null });
        when(joinPoint.proceed()).thenReturn(null);

        aspect.logAround(joinPoint);

        assertThat(messages()).containsExactly(
            "Enter: loggedMethod() with argument types = [String, String, Optional, null]",
            "Exit: loggedMethod() with result type = null"
        );
    }

    @Test
    void anAnonymousClassIsLoggedWithItsFullName() throws Throwable {
        Object anonima = new Object() {};
        when(joinPoint.getArgs()).thenReturn(new Object[] { anonima });
        when(joinPoint.proceed()).thenReturn(null);

        aspect.logAround(joinPoint);

        assertThat(messages().getFirst()).isEqualTo("Enter: loggedMethod() with argument types = [" + anonima.getClass().getName() + "]");
    }

    @Test
    void logAfterThrowingLogsExceptionWithStackTraceInDevProfile() {
        aspect.logAfterThrowing(joinPoint, new IllegalStateException("failed"));

        assertThat(messages()).containsExactly("Exception in loggedMethod() with cause = 'NULL' and exception = 'failed'");
        assertThat(appender.list.getFirst().getThrowableProxy()).isNotNull();
    }

    @Test
    void logAfterThrowingLogsCauseOnlyOutsideDevProfile() {
        when(env.acceptsProfiles(any(Profiles.class))).thenReturn(false);

        aspect.logAfterThrowing(joinPoint, new IllegalStateException("failed", new RuntimeException("root")));

        assertThat(messages()).containsExactly("Exception in loggedMethod() with cause = java.lang.RuntimeException: root");
        assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
    }
}
