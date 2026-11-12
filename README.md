# turnos-service

[![CI](https://github.com/MLucentiniProgramacion2/turnos-service/actions/workflows/ci.yml/badge.svg)](https://github.com/MLucentiniProgramacion2/turnos-service/actions/workflows/ci.yml)

Servicio de **turnos y reservas** del proyecto integrador 2026 (Programación II). Es el dueño de
los usuarios finales: registra, autentica y **emite el JWT** que la app KMP usa con este servicio y
con el de catálogo (ver [ADR 0003](docs/adr/0003-emision-jwt-y-usuarios.md)). Además va a
construir la disponibilidad y gestionar el proceso de reserva contra la API y el Kafka de la
cátedra.

La base (usuarios, seguridad, JWT, configuración y Liquibase) se generó con JHipster 9.4 como
monolito sin cliente web. Las funcionalidades de negocio se escriben a mano con arquitectura
hexagonal.

## Requisitos

- Java 25 (Temurin), por ejemplo con SDKMAN: `sdk install java 25.0.4-tem`
- Docker con Docker Compose (para la base y para los tests con Testcontainers)

No hace falta instalar Maven: se usa el wrapper `./mvnw`.

## Configuración

```bash
cp .env.example .env
```

Completar el `.env` (ignorado por Git):

- `TURNOS_DB_*`: base PostgreSQL propia del servicio. `TURNOS_DB_HOST` es `localhost` por
  defecto.
- `JWT_BASE64_SECRET`: clave de firma del JWT. Se genera con
  `openssl rand -base64 64 | tr -d '\n'` y tiene que ser **exactamente la misma** que en
  catalogo-service, que valida los tokens con ella. Si falta o está vacía, la aplicación no
  arranca.
- `CATEDRA_API_URL` y `CATEDRA_JWT`: API REST de la cátedra y JWT técnico de la cuenta del
  proyecto, los mismos que en catalogo-service. Solo accesible por ZeroTier. Sin alguno de los dos,
  la aplicación no arranca ([ADR 0004](docs/adr/0004-cliente-http-catedra.md)).
- `CATALOGO_API_URL`: servicio de catálogo (local: `http://localhost:8081`). Sin ella, la aplicación
  no arranca.

Opcionales, con su valor por defecto:

| Variable | Por defecto | Para qué |
| --- | --- | --- |
| `CATEDRA_CONNECT_TIMEOUT`, `CATEDRA_READ_TIMEOUT` | `2s`, `5s` | Timeouts de la cátedra (DPT-08) |
| `CATALOGO_CONNECT_TIMEOUT`, `CATALOGO_READ_TIMEOUT` | `2s`, `5s` | Timeouts del catálogo (DPT-13) |
| `SERVICE_TOKEN_LIFETIME` | `5m` | Vida del token de servicio hacia el catálogo, de `30s` a `5m`; con más, la aplicación no arranca ([ADR 0005](docs/adr/0005-token-de-servicio.md)) |

## Uso

```bash
docker compose up -d   # levanta la base turnos-db (PostgreSQL 18, puerto 5434 del host)
./mvnw                 # corre la app en el perfil dev, en http://localhost:8080
```

Al arrancar, Liquibase aplica las migraciones. Para comprobar que está en marcha:

```bash
curl http://localhost:8080/management/health
```

Registro e inicio de sesión:

```bash
curl -X POST http://localhost:8080/api/register -H 'Content-Type: application/json' \
  -d '{"login":"ana.perez","password":"una-clave","firstName":"Ana","lastName":"Pérez","email":"ana@example.com","langKey":"es"}'
curl -X POST http://localhost:8080/api/authenticate -H 'Content-Type: application/json' \
  -d '{"username":"ana.perez","password":"una-clave"}'   # devuelve {"id_token": "..."}
```

## Administrador

No hay usuarios por defecto. Para tener un administrador (opcional, DPT-14), registrarse como
cualquier usuario y después agregarle `ROLE_ADMIN` en la base:

```bash
docker exec -it turnos-db psql -U turnos_app -d turnos -c \
  "INSERT INTO jhi_user_authority (user_id, authority_name)
   SELECT id, 'ROLE_ADMIN' FROM jhi_user WHERE login = 'ana.perez' ON CONFLICT DO NOTHING;"
```

(Usar el usuario y la base de `TURNOS_DB_USER` y `TURNOS_DB_NAME` si son otros.) El rol vale desde
el próximo login: los permisos viajan en el token.

El servicio no envía correos, así que un usuario creado por un admin en `/api/admin/users` queda
con una contraseña aleatoria y **no puede iniciar sesión**. El alta normal es el registro
(`/api/register`); la gestión de admin sirve para consultar, editar y desactivar usuarios.

## Modelo de datos

Además de las tablas de JHipster (`jhi_user`, `jhi_authority`, `jhi_user_authority`), las de la
feature `reservation`, con changelogs propios en `config/liquibase/changelog/reservation/`:

- **`reservation_process`**: un proceso de reserva por fila, desde que se pide el hold hasta su
  estado final.
  - `id` es un UUID, que es lo que ve la app.
  - El dueño es `user_id` (FK a `jhi_user`, DPT-21); `user_login` es solo para mostrar.
  - Guarda el turno, los datos históricos del profesional (DPT-12), el paciente y los IDs y datos
    de la cátedra.
  - `version` es para el bloqueo optimista (DPT-09).
  - **Índice único parcial** `uq_reservation_process_one_intermediate_per_user`: un solo proceso
    intermedio por usuario (DPT-17).
- **`processed_event`**: los `eventId` de los eventos de Kafka ya procesados, como clave primaria
  (DPT-15).

Estados del proceso (DPT-05, diagrama en [`docs/use-cases/README.md`](docs/use-cases/README.md)):

- **intermedios:** `CREATING_HOLD`, `HOLD_CREATED`, `WAITING_FOR_PHONE_REQUEST`, `PHONE_REQUESTED`,
  `PHONE_SUBMITTED` y `PHONE_REJECTED`;
- **estable:** `CONFIRMED`, que solo sale a `CANCELLED`;
- **finales:** `CANCELLED`, `EXPIRED`, `INVALID` y `FAILED`.

## Tests

```bash
./mvnw clean verify            # todos: unitarios y de integración
./mvnw test -Dtest=NombreTest  # un test unitario
```

Los tests de integración levantan su propio PostgreSQL con Testcontainers, así que Docker tiene que
estar corriendo. No usan el `.env`: el perfil de test tiene una clave JWT fija.

### Comprobaciones manuales

Son clases `*ManualCheck` en `src/test`. No corren en el build (el nombre no coincide con
`*Test` ni `*IT`) y además piden una propiedad. Leen las variables del `.env` y nunca imprimen
tokens ni URLs.

```bash
# Ocupaciones reales de la cátedra (un GET, sin efectos). Necesita ZeroTier.
./mvnw test -Dtest=CatedraOccupancyManualCheck -Dcatedra.manual=true -Dprofesional=202 -Ddesde=2026-12-14 -Dhasta=2026-12-18
# Tres rechazos de la cátedra real que no crean nada: PROFESSIONAL_NOT_FOUND, INVALID_SLOT y HOLD_NOT_FOUND.
./mvnw test -Dtest=CatedraHoldRejectionsManualCheck -Dcatedra.manual=true
# La agenda de un profesional en el catálogo local, con un token de servicio firmado como lo firma turnos.
./mvnw test -Dtest=ProfessionalAgendaManualCheck -Dcatalogo.manual=true -Dprofesional=201
# Con turnos corriendo: su propio token de servicio da 403 en /api/account (imprime solo el status).
./mvnw test -Dtest=ServiceTokenOnTurnosManualCheck -Dturnos.manual=true
```

GitHub Actions corre `./mvnw -B -ntp clean verify` en cada pull request hacia `develop` o `main` y
en cada push a `develop` (workflow [`ci.yml`](.github/workflows/ci.yml)). Si falla, los reportes de
`surefire` y `failsafe` quedan como artifact por 7 días.

## Notas

- `src/main/docker/` es lo que genera JHipster y todavía no se usa: la única base del servicio es la
  del `docker-compose.yml` de la raíz.
- La clave JWT de `src/main/docker/jhipster-control-center.yml` (y de `.yo-rc.json` y del perfil de
  test) es la **clave de test, pública a propósito**: no sirve para firmar ni validar tokens reales.
- Decisiones de arquitectura en [`docs/adr/`](docs/adr) y casos de uso en
  [`docs/use-cases/`](docs/use-cases/README.md).
