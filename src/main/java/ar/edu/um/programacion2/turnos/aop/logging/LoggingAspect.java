package ar.edu.um.programacion2.turnos.aop.logging;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import tech.jhipster.config.JHipsterConstants;

/**
 * Aspect for logging execution of service and repository Spring components.
 *
 * By default, it only runs with the "dev" profile.
 * <p>
 * <strong>Logs the types of the arguments and of the result, never their values</strong> (issue #10). JHipster logged
 * the values with {@code toString()}, and in {@code dev} that printed the user's JWT ({@code Exit: authorize()}: the
 * {@code toString()} of a {@code ResponseEntity} includes its headers), the passwords in plain text
 * ({@code Enter: registerUser()} and {@code changePassword()}) and personal data (email and names of
 * {@code AdminUserDTO}). With types only, no current or future method can leak a value through this aspect.
 */
@Aspect
public class LoggingAspect {

    private final Environment env;

    public LoggingAspect(Environment env) {
        this.env = env;
    }

    /**
     * Pointcut that matches all repositories, services and Web REST endpoints.
     */
    @Pointcut(
        """
        within(@org.springframework.stereotype.Repository *)
        || within(@org.springframework.stereotype.Service *)
        || within(@org.springframework.web.bind.annotation.RestController *)
        """
    )
    public void springBeanPointcut() {
        // Method is empty as this is just a Pointcut, the implementations are in the advice methods.
    }

    /**
     * Pointcut that matches all Spring beans in the application's main packages.
     */
    @Pointcut(
        """
        within(ar.edu.um.programacion2.turnos.repository..*)
        || within(ar.edu.um.programacion2.turnos.service..*)
        || within(ar.edu.um.programacion2.turnos.web.rest..*)
        """
    )
    public void applicationPackagePointcut() {
        // Method is empty as this is just a Pointcut, the implementations are in the advice methods.
    }

    /**
     * Retrieves the {@link Logger} associated to the given {@link JoinPoint}.
     *
     * @param joinPoint join point we want the logger for.
     * @return {@link Logger} associated to the given {@link JoinPoint}.
     */
    private Logger logger(JoinPoint joinPoint) {
        return LoggerFactory.getLogger(joinPoint.getSignature().getDeclaringTypeName());
    }

    /**
     * Advice that logs methods throwing exceptions.
     *
     * @param joinPoint join point for advice.
     * @param e exception.
     */
    @AfterThrowing(pointcut = "applicationPackagePointcut() && springBeanPointcut()", throwing = "e")
    public void logAfterThrowing(JoinPoint joinPoint, Throwable e) {
        logException(joinPoint, e);
    }

    /**
     * Advice that logs when a method is entered and exited.
     *
     * @param joinPoint join point for advice.
     * @return result.
     * @throws Throwable rethrows the exception thrown by the advised method.
     */
    @Around("applicationPackagePointcut() && springBeanPointcut()")
    public Object logAround(ProceedingJoinPoint joinPoint) throws Throwable {
        var log = logger(joinPoint);
        if (log.isDebugEnabled()) {
            log.debug("Enter: {}() with argument types = {}", joinPoint.getSignature().getName(), typesOf(joinPoint.getArgs()));
        }
        try {
            Object result = joinPoint.proceed();
            logExit(log, joinPoint, result);
            return result;
        } catch (IllegalArgumentException e) {
            logIllegalArgument(log, joinPoint);
            throw e;
        }
    }

    private void logExit(Logger log, JoinPoint joinPoint, Object result) {
        if (log.isDebugEnabled()) {
            log.debug("Exit: {}() with result type = {}", joinPoint.getSignature().getName(), typeOf(result));
        }
    }

    private void logIllegalArgument(Logger log, JoinPoint joinPoint) {
        if (log.isErrorEnabled()) {
            log.error("Illegal argument types: {} in {}()", typesOf(joinPoint.getArgs()), joinPoint.getSignature().getName());
        }
    }

    private static String typesOf(Object[] values) {
        return Arrays.stream(values).map(LoggingAspect::typeOf).collect(Collectors.joining(", ", "[", "]"));
    }

    /** The simple name of the runtime type; the full name for anonymous classes, whose simple name is empty. */
    private static String typeOf(Object value) {
        if (value == null) {
            return "null";
        }
        String simpleName = value.getClass().getSimpleName();
        return simpleName.isEmpty() ? value.getClass().getName() : simpleName;
    }

    private void logException(JoinPoint joinPoint, Throwable e) {
        if (env.acceptsProfiles(Profiles.of(JHipsterConstants.SPRING_PROFILE_DEVELOPMENT))) {
            logger(joinPoint).error(
                "Exception in {}() with cause = '{}' and exception = '{}'",
                joinPoint.getSignature().getName(),
                e.getCause() != null ? e.getCause() : "NULL",
                e.getMessage(),
                e
            );
        } else {
            logger(joinPoint).error(
                "Exception in {}() with cause = {}",
                joinPoint.getSignature().getName(),
                e.getCause() != null ? String.valueOf(e.getCause()) : "NULL"
            );
        }
    }
}
