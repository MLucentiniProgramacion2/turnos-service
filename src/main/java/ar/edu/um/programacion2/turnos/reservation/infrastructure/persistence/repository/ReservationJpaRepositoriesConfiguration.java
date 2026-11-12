package ar.edu.um.programacion2.turnos.reservation.infrastructure.persistence.repository;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Registra los repositorios de Spring Data de la feature.
 * <p>
 * El {@code @EnableJpaRepositories} de {@code DatabaseConfiguration} (JHipster) solo busca en {@code turnos.repository},
 * y al estar declarado, Spring Boot ya no busca en el resto. La feature declara el suyo, limitado a su paquete, sin tocar
 * la configuración generada (como el catálogo).
 */
@Configuration
@EnableJpaRepositories(basePackageClasses = ReservationJpaRepositoriesConfiguration.class)
public class ReservationJpaRepositoriesConfiguration {}
