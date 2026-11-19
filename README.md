# turnos-service

[![CI](https://github.com/MLucentiniProgramacion2/turnos-service/actions/workflows/ci.yml/badge.svg)](https://github.com/MLucentiniProgramacion2/turnos-service/actions/workflows/ci.yml)

Servicio de **turnos y reservas** del proyecto integrador 2026 (Programación II). Es el dueño de
los usuarios finales: registra, autentica y **emite el JWT** que la app KMP usa con este servicio y
con el de catálogo (ver [ADR 0003](docs/adr/0003-emision-jwt-y-usuarios.md)). Además construye la
disponibilidad (con la agenda vigente que pide al servicio de catálogo y las ocupaciones de la cátedra),
gestiona el proceso de reserva contra la API y el Kafka de la cátedra, deja a cada usuario consultar y
cancelar solo sus reservas, y reconcilia los procesos que quedaron pendientes.

- Estados, idempotencia y recuperación: [`docs/reservas.md`](docs/reservas.md).
- Tablas y propiedad de los datos: [`docs/modelo-de-datos.md`](docs/modelo-de-datos.md).
- Contrato REST con la app: [`docs/api-rest.md`](docs/api-rest.md).

La base (usuarios, seguridad, JWT, configuración y Liquibase) se generó con JHipster 9.4 como
monolito sin cliente web. Las funcionalidades de negocio se escriben a mano con arquitectura
hexagonal.

## Requisitos

- Docker con Docker Compose (probado con Engine 29.8 y Compose v5.5; el Compose usa `env_file` con
  `format: raw`, que las versiones viejas no entienden)
- Java 25 (Temurin) solo para desarrollar y correr los tests, por ejemplo con SDKMAN:
  `sdk install java 25.0.4-tem`. Para levantar el servicio con Docker Compose no hace falta: la
  imagen se compila adentro de Docker.

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
- `CATEDRA_KAFKA_BOOTSTRAP_SERVERS`, `CATEDRA_KAFKA_ACTIONS_TOPIC`, `CATEDRA_KAFKA_PHONE_TOPIC`,
  `CATEDRA_KAFKA_CONSUMER_GROUP` y `CATEDRA_GROUP_ID`: Kafka de la cátedra, los dos topics y el
  `groupId` de `integration`, y el grupo propio de turnos (`kafkaConsumerGroupId` con el sufijo
  `-turnos`, nunca el del catálogo). Sin alguna, la aplicación no arranca; con el broker caído, sí
  ([ADR 0006](docs/adr/0006-kafka-en-turnos.md)).
- `CATALOGO_API_URL`: servicio de catálogo (local: `http://localhost:8081`). Sin ella, la aplicación
  no arranca.

Opcionales, con su valor por defecto:

| Variable | Por defecto | Para qué |
| --- | --- | --- |
| `CATEDRA_CONNECT_TIMEOUT`, `CATEDRA_READ_TIMEOUT` | `2s`, `5s` | Timeouts de la cátedra (DPT-08) |
| `CATALOGO_CONNECT_TIMEOUT`, `CATALOGO_READ_TIMEOUT` | `2s`, `5s` | Timeouts del catálogo (DPT-13) |
| `SERVICE_TOKEN_LIFETIME` | `5m` | Vida del token de servicio hacia el catálogo, de `30s` a `5m`; con más, la aplicación no arranca ([ADR 0005](docs/adr/0005-token-de-servicio.md)) |
| `RESERVATION_TIME_ZONE` | `America/Argentina/Mendoza` | Zona horaria de atención: "hoy" y "ahora" para ofrecer y reservar turnos (DPT-10). Una zona que no existe impide arrancar |
| `RESERVATION_BOOKING_HORIZON_DAYS` | `60` | Hasta cuántos días adelante se ofrecen turnos, de 1 a 365 |
| `RESERVATION_RECONCILIATION_INTERVAL` | `60s` | Cada cuánto corre la reconciliación de procesos pendientes (CU-11), desde que termina la anterior; la primera corre al arrancar. De `10s` a `10m` |
| `TURNOS_APP_BIND` | `127.0.0.1` | Solo Docker Compose: interfaz del host donde se publica el `8080`. Para un teléfono físico, la IP de la red local; nunca `0.0.0.0` |

## Uso

### Con Docker Compose (base y aplicación)

```bash
docker compose up -d --build --wait   # construye la imagen y levanta turnos-db y turnos-app (perfil prod)
docker compose logs -f turnos-app     # los logs de la aplicación (Ctrl+C no la detiene)
docker compose stop                   # apaga en orden; el contenedor sale con 143 (SIGTERM)
```

- `--build` reconstruye la imagen ([`Dockerfile`](Dockerfile), en dos etapas: compila con el
  wrapper y el perfil `prod` y corre en un JRE 25). Sin `--build`, el Compose usa la imagen que ya
  está (`turnos-service:local`). La primera vez tarda unos 2 minutos (baja las dependencias de Maven);
  después, unos 20 segundos.
- El contenedor usa el mismo `.env`. El [`docker-compose.yml`](docker-compose.yml) pisa la base
  (`turnos-db:5432`), el catálogo (`host.docker.internal:8081`) y el perfil (`prod`).
- `turnos-app` queda `healthy` cuando responde `/management/health/readiness` (unos 20 s). La
  cátedra solo se alcanza por ZeroTier, también desde el contenedor.
- El catálogo es otro Compose (o `./mvnw`) y se alcanza por el host: levantarlo antes, con su puerto
  publicado también en la IP del gateway de Docker (lo hace su Compose). Apagar en el orden inverso:
  primero turnos y después el catálogo.
- `docker compose stop` espera hasta 90 s el cierre ordenado. **Nunca `docker compose down -v`**:
  borra el volumen `turnos-service_turnos-db-data`, con los usuarios y las reservas.

### Para desarrollar (base en Docker, aplicación con `./mvnw`)

```bash
docker compose stop turnos-app      # si estaba corriendo: usa el mismo puerto 8080
docker compose up -d turnos-db      # solo la base (PostgreSQL 18, puerto 5434 del host)
./mvnw                              # la app en el perfil dev, en http://localhost:8080
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

### La reserva

Con el catálogo levantado (puerto 8081) y ZeroTier conectado. El contrato completo, con cada `code`
de error, está en [`docs/api-rest.md`](docs/api-rest.md).

| Endpoint | Caso de uso | Respuesta |
| --- | --- | --- |
| `GET /api/professionals/{id}/availability?from=&to=` | CU-03 | turnos libres por día (hasta 7 días, hasta hoy + 60) |
| `POST /api/reservation-processes` | CU-04 | `202` con el proceso; `200` si el mismo turno ya estaba en curso |
| `POST /api/reservation-processes/{id}/phone` | CU-06 | `202` con el proceso en `PHONE_SUBMITTED` |
| `GET /api/reservation-processes/{id}` | CU-08 | `200` con el proceso propio (la app lo consulta cada 2 s) |
| `GET /api/reservation-processes?status=&page=&size=` | CU-09 | `200` con una página de procesos propios, convergidos con la cátedra, y `catedraUpToDate` |
| `POST /api/reservation-processes/{id}/cancel` | CU-10 | `200` con el proceso en `CANCELLED` (idempotente) |
| `GET /api/admin/reservation-processes` y `.../{id}` | CU-12 | solo `ROLE_ADMIN`: los procesos de todos, de solo lectura |

La reconciliación (CU-11) no tiene endpoint: corre al arrancar y cada 60 s.

El pedido de teléfono (CU-05) y el resultado (CU-07) llegan por Kafka: no tienen endpoint. Para no
dejar el token en el historial de la terminal, se guarda en una variable sin mostrarlo:

```bash
read -r -s -p 'Clave: ' CLAVE; echo
TOKEN=$(jq -n --arg u ana.perez --arg p "$CLAVE" '{username:$u,password:$p}' \
  | curl -s -X POST http://localhost:8080/api/authenticate -H 'Content-Type: application/json' -d @- | jq -r .id_token)
unset CLAVE
curl -s "http://localhost:8080/api/professionals/202/availability?from=2026-12-14&to=2026-12-18" \
  -H "Authorization: Bearer $TOKEN" | jq
```

### Un usuario trabado en una reserva en curso

Un proceso en un estado intermedio impide iniciar otra reserva (índice único parcial, DPT-17). La
**reconciliación** (CU-11, al arrancar y cada 60 s) los cierra solos, preguntándole a la cátedra con los IDs
guardados:

| Caso | Se destraba solo |
| --- | --- |
| `CREATING_HOLD` que quedó a medias (por ejemplo, el hold se creó y no se pudo guardar) | Pasa a `FAILED` con más de 1 minuto sin cambios: entre 1 y 2 minutos |
| `HOLD_CREATED` porque el confirm no respondió | Con más de 1 minuto: si la cátedra no lo tiene, se reintenta el confirm; si lo tiene, converge. Entre 1 y 2 minutos |
| Se perdió un evento (esperando el pedido de teléfono o el resultado) | Pasado `expiresAt` + 1 minuto, según lo que informe la cátedra (`CONFIRMED`, `CANCELLED` o `EXPIRED`): entre 11 y 12 minutos después del hold |
| La cátedra sigue en `PHONE_PENDING` | Se espera hasta `expiresAt` + 10 minutos; después pasa a `EXPIRED` con una advertencia |

Si la cátedra no responde, el proceso queda como está y lo toma la ejecución siguiente. Como **último
recurso** (por ejemplo, con la cátedra caída por mucho tiempo), se puede pasar a `FAILED` a mano, **después
de comprobar en la cátedra** (`GET /api/appointments`) que ese proceso no quedó confirmado:

```bash
docker exec -it turnos-db psql -U turnos_app -d turnos -c \
  "UPDATE reservation_process SET status = 'FAILED', closing_reason = 'MANUAL_UNLOCK',
     status_changed_at = now(), version = version + 1
   WHERE id = '<id del proceso>' AND status IN ('CREATING_HOLD', 'HOLD_CREATED', 'WAITING_FOR_PHONE_REQUEST',
     'PHONE_REQUESTED', 'PHONE_SUBMITTED', 'PHONE_REJECTED');"
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

Además de las tablas de JHipster (`jhi_user`, `jhi_authority`, `jhi_user_authority`), las de la feature
`reservation`, con changelogs propios en `config/liquibase/changelog/reservation/`: `reservation_process` (un
proceso de reserva por fila, del hold a su estado final, con el dueño en `user_id` y un índice único parcial
que deja una sola reserva en curso por usuario) y `processed_event` (los `eventId` de Kafka ya procesados).
Cada columna, las restricciones, qué datos son de turnos, del catálogo y de la cátedra, y cómo se protegen los
datos personales: [`docs/modelo-de-datos.md`](docs/modelo-de-datos.md).

Turnos **no guarda el catálogo**: la agenda se pide al servicio de catálogo en cada consulta (CU-03) y en cada
reserva (CU-04). Del profesional solo quedan los datos históricos del proceso, para mostrar.

Estados del proceso (DPT-05, diagrama en [`docs/use-cases/README.md`](docs/use-cases/README.md)):

- **intermedios:** `CREATING_HOLD`, `HOLD_CREATED`, `WAITING_FOR_PHONE_REQUEST`, `PHONE_REQUESTED`,
  `PHONE_SUBMITTED` y `PHONE_REJECTED`;
- **estable:** `CONFIRMED`, que solo sale a `CANCELLED`;
- **finales:** `CANCELLED`, `EXPIRED`, `INVALID` y `FAILED`.

## Evidencias del enunciado (§11)

Las que corresponden a turnos, con el test que la prueba y la comprobación manual contra la cátedra real
(los logs están en la carpeta `evidencias` del proyecto, fuera de este repo):

| Evidencia (§11) | Test automatizado | Comprobación manual |
| --- | --- | --- |
| Una reserva confirmada mediante el flujo REST y Kafka | `ReservationFlowsIT.unaReservaQueTerminaConfirmada`, `ReservationStage3FlowsIT.confirmadaConsultadaListadaYCanceladaConElEventoTardioSinEfecto` | `12-arranque-con-comprobacion.log`, `12-reserva.json`; `14-arranque-2.log` (la de A, cerrada por CU-11) |
| Una reserva que expira sin completar la información solicitada | `ReservationFlowsIT.unaReservaQueVenceSinInformarElTelefono`, `ReservationStage3FlowsIT.unaReservaQueExpiraLaAppLaVeExpiradaYSePuedeVolverAReservar` y `unPedidoDeTelefonoQueNoLlegaNuncaTerminaExpiradoPorLaReconciliacion` | `12-reserva-que-vence.json`; `14-arranque-2.log` (la de B) |
| Consulta y cancelación de una reserva propia, sin acceso cruzado entre usuarios comunes | `ReservationStage3FlowsIT.otroUsuarioNoVeNiCancelaElProcesoAunqueLaCatedraDevuelvaLaReserva`, `CancelReservationControllerIT`, `MyReservationsControllerIT`, `ReservationProcessStatusControllerIT` | `14-arranque-1.log` (CU-08, CU-09, `404` cruzados y la cancelación de la 5857) |
| Procesamiento idempotente de un mensaje duplicado | `ReservationEventListenerIT`, `ReservationStage3FlowsIT.unAppointmentConfirmedDespuesDeExpiredNoLoReabreYUnDuplicadoNoRepiteEfectos` | `14-arranque-3.log` (los eventos tardíos contra la cátedra real); el duplicado no se puede forzar en la cátedra |
| Separación efectiva de datos entre los dos servicios | `HexagonalArchitectureTest`; la base propia (`docker-compose.yml`) | [`docs/modelo-de-datos.md`](docs/modelo-de-datos.md#3-la-propiedad-qué-es-de-quién) |
| Rechazo de accesos no autenticados o no autorizados | La matriz de autorización de cada `*ControllerIT` (sin token `401`, token de servicio `403`, usuario en `/api/admin/**` `403`), `ServiceTokenRejectionIT` | `10-token-servicio-en-turnos.log` |
| Pruebas automatizadas de los comportamientos principales | `./mvnw clean verify` (sección siguiente) | — |

## Tests

```bash
./mvnw clean verify            # todos: unitarios y de integración
./mvnw test -Dtest=NombreTest  # un test unitario
```

Los tests de integración levantan su propio PostgreSQL y sus brokers de Kafka con Testcontainers,
así que Docker tiene que estar corriendo. No usan el `.env`: el perfil de test tiene una clave JWT fija
y nombres de topics inventados. Los de Kafka miden plazos reales (hasta 8 s) y uno pausa su propio
broker con `docker pause`.

Para correr un test de integración suelto con la misma configuración que `verify`:

```bash
./mvnw test -Dtest=NombreIT -Dspring.profiles.active=test,testdev
```

### Comprobaciones manuales

Son clases `*ManualCheck` en `src/test`. No corren en el build (el nombre no coincide con
`*Test` ni `*IT`) y además piden una propiedad. Leen las variables del `.env` y nunca imprimen
tokens ni URLs.

```bash
# Ocupaciones reales de la cátedra (un GET, sin efectos). Necesita ZeroTier.
./mvnw test -Dtest=CatedraOccupancyManualCheck -Dcatedra.manual=true -Dprofesional=202 -Ddesde=2026-12-14 -Dhasta=2026-12-18
# Las reservas reales de un paciente (un GET, sin efectos), con páginas de 1 para recorrer la paginación de verdad.
./mvnw test -Dtest=CatedraAppointmentsManualCheck -Dcatedra.manual=true -Dpaciente=user-1101 -Ddesde=2027-01-05 -Dhasta=2027-01-05 -Dsize=1
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

- El Compose del servicio es el `docker-compose.yml` de la raíz. Lo que queda en `src/main/docker/`
  (monitoreo, Sonar, JHipster Control Center) es lo que generó JHipster y no se usa.
- La clave JWT de `src/main/docker/jhipster-control-center.yml` (y de `.yo-rc.json` y del perfil de
  test) es la **clave de test, pública a propósito**: no sirve para firmar ni validar tokens reales.
- Decisiones de arquitectura en [`docs/adr/`](docs/adr) y casos de uso en
  [`docs/use-cases/`](docs/use-cases/README.md).
