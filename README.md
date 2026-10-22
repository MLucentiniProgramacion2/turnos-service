# turnos-service

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

## Tests

```bash
./mvnw clean verify            # todos: unitarios y de integración
./mvnw test -Dtest=NombreTest  # un test unitario
```

Los tests de integración levantan su propio PostgreSQL con Testcontainers, así que Docker tiene que
estar corriendo. No usan el `.env`: el perfil de test tiene una clave JWT fija.

## Notas

- `src/main/docker/` es lo que genera JHipster y todavía no se usa: la única base del servicio es la
  del `docker-compose.yml` de la raíz.
- La clave JWT de `src/main/docker/jhipster-control-center.yml` (y de `.yo-rc.json` y del perfil de
  test) es la **clave de test, pública a propósito**: no sirve para firmar ni validar tokens reales.
- Decisiones de arquitectura en [`docs/adr/`](docs/adr) y casos de uso en
  [`docs/use-cases/`](docs/use-cases/README.md).
