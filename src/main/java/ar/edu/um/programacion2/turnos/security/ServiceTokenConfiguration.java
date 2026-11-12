package ar.edu.um.programacion2.turnos.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registra {@link ServiceTokenProperties}, que valida la vida del token de servicio al arrancar. */
@Configuration
@EnableConfigurationProperties(ServiceTokenProperties.class)
public class ServiceTokenConfiguration {}
