# ADR 0001: PostgreSQL como base de datos del servicio de turnos

- **Estado:** aceptada
- **Fecha:** 2026-10-21

## Contexto

El servicio de turnos y reservas guarda los usuarios finales (registro y login, con la gestión de
usuarios de JHipster), los procesos de reserva con su estado local (DPT-05) y su dueño (DPT-21),
los datos descriptivos históricos del profesional (DPT-12) y los `eventId` de Kafka ya procesados
(DPT-15). No guarda una réplica del catálogo: la agenda vigente se pide al servicio de catálogo en
cada operación.

Requisitos del enunciado (`PROJECT_STATEMENT-v1.md`) que condicionan la elección:

- **§3:** la persistencia principal debe usar un gestor de base de datos **servidor**. **No se
  aceptan H2, SQLite ni otras bases embebidas o en memoria.** El motor lo elige el alumno.
- **§3:** cada servicio es propietario exclusivo de sus datos y administra **sus propias
  migraciones**. No se puede acceder a las tablas del otro servicio ni usar una base compartida
  como integración. Se puede compartir una instancia física solo con bases lógicas o esquemas
  separados y **usuarios sin permisos cruzados**.
- **§3:** la infraestructura local se levanta con Docker Compose.
- **§4.2:** cada proceso de reserva y cada reserva local quedan asociados al usuario final que los
  inició.
- **§8:** hay que tolerar mensajes Kafka duplicados, repeticiones por timeout y reinicios con
  operaciones pendientes, lo que exige persistir el progreso de forma consistente.
- **§12:** hay que documentar el modelo de datos y las decisiones técnicas.

Además, los casos de uso fijan dos requisitos propios sobre la base:

- **DPT-17:** un solo proceso intermedio por usuario. Dos pedidos concurrentes para turnos
  distintos no pueden crear dos procesos en curso; la garantía tiene que darla la base, no un
  chequeo previo en la aplicación, que deja una carrera entre la lectura y la escritura.
- **DPT-15:** el `eventId` recibido se registra con clave única **en la misma transacción** que el
  cambio de estado del proceso.

El repositorio de referencia de la cátedra (arquitectura hexagonal) usa H2, que no se puede
usar como persistencia principal. Por eso hace falta elegir otro motor.

## Decisión

Usar **PostgreSQL** (imagen `postgres:18.1-alpine`, la misma versión fija que el servicio de
catálogo) como base propia del servicio, levantada con Docker Compose: base `turnos` y usuario
`turnos_app`, con credenciales por variables de entorno (`.env`, no versionado).

La base y el usuario son **exclusivos de turnos**: el servicio de catálogo tiene su propia base
(`catalogo`) y su propio usuario (`catalogo_app`), en un contenedor aparte, y ninguno de los dos
usuarios tiene permisos sobre la base del otro (§3).

DPT-17 se implementa con un **índice único parcial**: único sobre el dueño, aplicado solo a las
filas cuyo estado es intermedio (`CREATING_HOLD`, `HOLD_CREATED`, `WAITING_FOR_PHONE_REQUEST`,
`PHONE_REQUESTED`, `PHONE_SUBMITTED`, `PHONE_REJECTED`). Los procesos en `CONFIRMED` o en un
estado final no participan del índice, así que un usuario puede tener cualquier cantidad.

## Alternativas consideradas

| Alternativa | Motivo para descartarla |
| --- | --- |
| **MySQL / MariaDB** | Cumple §3, pero no tiene índices parciales: DPT-17 habría que emularlo con una columna generada que valga `NULL` fuera de los estados intermedios, más difícil de leer y de mantener. Además, las sentencias DDL hacen commit implícito: una migración que falla puede dejar el esquema a medias. |
| **MongoDB** | Se aparta de JPA/Hibernate, que es la herramienta de persistencia de la materia. Registrar el `eventId` y cambiar el estado en una misma transacción (DPT-15) requiere transacciones multi-documento, que necesitan un *replica set*. |
| **H2 / SQLite** | Prohibidas por §3 como persistencia principal. |

## Consecuencias

- **Positivas:**
  - DPT-17 queda garantizado por la base: el segundo `INSERT` concurrente falla por violación de
    unicidad y la aplicación lo traduce a `409`, sin carreras.
  - DPT-15 se resuelve con una tabla con clave única y una sola transacción: un evento duplicado
    falla al insertar y no aplica dos veces el cambio de estado.
  - Las transacciones cubren también el DDL: una migración que falla se revierte completa.
  - Tipos que calzan con el contrato: `time` para horas `HH:mm:ss`, `date` para `yyyy-MM-dd` y
    `timestamptz` para instantes ISO-8601 UTC como `expiresAt`.
  - Mismo motor y misma versión que el catálogo: una sola forma de operar, respaldar y testear,
    sin compartir datos.
  - Buen soporte en Testcontainers para tests de integración, en Spring Boot y en JHipster.
- **Negativas:**
  - Hace falta Docker para desarrollar y correr los tests de integración.
  - El índice único parcial (`CREATE UNIQUE INDEX ... WHERE ...`) es propio de PostgreSQL: no es
    portable a otro motor sin rediseñar DPT-17.
- **Seguridad:** el puerto se expone solo en `127.0.0.1` y es configurable (por defecto `5434`),
  para no chocar con la base del servicio de catálogo (`5433`) y poder levantar las dos a la vez.
