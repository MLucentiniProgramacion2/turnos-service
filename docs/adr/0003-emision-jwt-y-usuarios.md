# ADR 0003: Turnos es el dueño de los usuarios y emite el JWT, firmado con HS512 y una clave compartida con el catálogo

- **Estado:** aceptada
- **Fecha:** 2026-10-22
- **Relacionada con:** [DPT-02, DPT-14, DPT-16, DPT-19, DPT-20 y DPT-21 de los casos de uso](../use-cases/README.md#decisiones-del-proyecto),
  [CU-01](../use-cases/CU-01-registrarse.md), [CU-02](../use-cases/CU-02-iniciar-sesion.md) y el
  ADR 0003 del servicio de catálogo (validación del JWT)

## Contexto

Requisitos del enunciado (`PROJECT_STATEMENT-v1.md`) que condicionan la decisión:

- **§3:** los dos servicios se comunican mediante contratos explícitos **protegidos con JWT**, y
  cada uno es propietario exclusivo de sus datos.
- **§3.2:** el usuario final se registra e inicia sesión desde KMP contra el backend del alumno,
  que **emite un JWT** para consumir las APIs protegidas. El usuario queda **activo
  inmediatamente**, sin verificar el correo; la recuperación de contraseña es **opcional**. El
  modelo de usuarios tiene que ser compatible con el de JHipster, y el id, la activación, las
  autoridades y la auditoría los administra el backend.
- **§9:** hashing de contraseñas, validar firma, vigencia y permisos de todo token, externalizar
  los secretos y aislar a cada usuario común de los datos de los demás.

Decisiones ya tomadas que se apoyan en esta:

- El registro y el login viven en **turnos**; el catálogo solo valida el JWT (13/10, ADR 0003 del
  catálogo).
- **DPT-16:** turnos llama al catálogo con un JWT técnico de servicio (`ROLE_SERVICE`,
  `aud=catalogo-service`, `iss=turnos-service`), distinto del JWT de usuario.
- **DPT-21:** el dueño de procesos y reservas es el `id` interno del usuario, no el login.

El profesor aprobó JHipster solo para la base (usuarios, seguridad, JWT y configuración). Hay que
elegir cómo se genera turnos y qué se cambia de lo que JHipster trae por defecto.

## Decisión

### Monolito JHipster sin cliente web

La base se genera con JHipster 9.4 como **monolito**, con autenticación JWT y **gestión de
usuarios**, y **sin cliente web** (`skipClient`). Es el espejo de la decisión del catálogo:

- Un **monolito** JHipster con JWT trae justo lo que turnos necesita como dueño de los usuarios:
  tablas `jhi_user` y `jhi_authority`, registro (`/api/register`), login (`/api/authenticate`),
  cuenta propia (`/api/account`), gestión de usuarios de admin (`/api/admin/users`) y el
  `JwtEncoder` que **emite** los tokens.
- Un **microservicio** JHipster (lo que usa el catálogo) solo valida tokens: no tiene usuarios ni
  login.
- **Sin cliente web:** la interfaz es la app KMP, que consume la API REST. Un frontend Angular o
  React agregaría código que no se usa.
- Sin *gateway* ni *service discovery*: la app KMP llama directo a turnos y al catálogo.

### Firma HS512 con la clave compartida con el catálogo

- Los tokens se firman con **HS512** (lo que trae JHipster) y una **clave simétrica compartida**
  con el catálogo, que valida con la misma clave.
- La clave se lee de la variable de entorno `JWT_BASE64_SECRET` (en `.env`, que no se versiona) y
  tiene que ser **exactamente el mismo valor** en los dos servicios. Se genera con
  `openssl rand -base64 64 | tr -d '\n'` (512 bits, lo que pide HS512).
- En dev y prod `jhipster.security.authentication.jwt.base64-secret` **no tiene valor por
  defecto**: si la variable falta o está vacía, **la aplicación no arranca** (falla al crear el
  `JwtEncoder` con `Empty key`). Se borró el perfil `secret-samples`, que el perfil dev activaba y
  traía una clave de ejemplo. Solo el perfil de test tiene una clave fija, falsa y pública a
  propósito.

La alternativa de firma asimétrica (RS256) se analiza y descarta en el ADR 0003 del catálogo; los
mismos motivos valen acá.

### Contenido del JWT de usuario

| Claim | Valor | Para qué |
| --- | --- | --- |
| `sub` | login | Identidad visible; la usa Spring Security como nombre del usuario. |
| `auth` | autoridades del usuario, separadas por espacio | Permisos (`ROLE_USER`, `ROLE_ADMIN`). |
| `userId` | `id` interno (número) | Dueño de procesos y reservas (DPT-21). |
| `iat`, `exp` | emisión y vencimiento | Vigencia: **24 h**, o **30 días** con `rememberMe` (valores de JHipster). |

El token **no** lleva `aud`, `iss` ni `ROLE_SERVICE` (DPT-16): son los del token técnico de
servicio, y así el catálogo distingue uno de otro. Si el usuario autenticado no tiene id, no se
emite el token. `SecurityUtils.getCurrentUserId()` lee el `userId` del token del pedido en curso.

**Por qué el id y no el login:** un admin puede cambiar el login de un usuario en
`/api/admin/users`. Si los procesos guardaran el login como dueño, al cambiarlo quedarían huérfanos
o, peor, pasarían a quien tome después ese login. El `id` no cambia nunca.

### Sin usuarios por defecto

Liquibase carga **solo las autoridades** (`ROLE_ADMIN` y `ROLE_USER`), en todos los perfiles. Se
quitaron los usuarios `admin/admin` y `user/user` que JHipster carga por defecto: tienen
contraseñas conocidas y el repo es público. Como el *changeset* inicial todavía no se había
aplicado en ninguna base, se editó directamente en vez de agregar uno que los borre.

El rol de administrador es opcional (DPT-14). Para tener uno, el usuario se **registra** como
cualquier otro y después se le agrega `ROLE_ADMIN` con SQL contra `turnos-db` (ver el README). El
cambio vale desde el próximo login, porque los permisos viajan en el token.

### Sin envío de correo

- El registro deja al usuario **activo al instante** (§3.2): sin clave de activación y sin mail.
  El backend fija el id, `activated`, las autoridades (solo `ROLE_USER`) y la auditoría, sin
  importar lo que mande el pedido.
- Un login o email ya usado se rechaza siempre. JHipster borraba el usuario no activado para dejar
  registrar otro con los mismos datos; sin activación por correo, un usuario desactivado solo puede
  serlo por un admin, y no se borra.
- Se quitaron el envío de correos (`MailService`, plantillas y `spring-boot-starter-mail`),
  `/api/activate` y la recuperación de contraseña (`/api/account/reset-password/**`), que es
  opcional en la consigna y sin correo no tiene cómo verificar a quién se le cambia la contraseña.
  El usuario autenticado puede cambiarla con `/api/account/change-password`.

### Validaciones de usuario

Viven en `AdminUserDTO`, el DTO que comparten el registro, `POST /api/account` y
`/api/admin/users`: `login` de 3 a 50 con el patrón de JHipster (DPT-20), `firstName` y `lastName`
obligatorios de 2 a 50 (DPT-02), `email` de 5 a 254 y `langKey` de 2 a 10. La contraseña va de 4 a
100.

### Sin `/api/users`

Se quitó `GET /api/users` (`PublicUserResource`), que devolvía el id y el login de **todos** los
usuarios a cualquier usuario autenticado. Choca con el aislamiento entre usuarios comunes del §9
(DPT-19) y no lo necesita ningún caso de uso. El listado completo queda solo para `ROLE_ADMIN` en
`/api/admin/users`.

## Alternativas consideradas

| Alternativa | Motivo para descartarla |
| --- | --- |
| **Microservicio JHipster con usuarios escritos a mano** | Habría que reimplementar registro, login, hashing y emisión del token, que JHipster ya trae probados y compatibles con el usuario de JHipster (§3.2). |
| **Firma asimétrica (RS256)** | Descartada en el ADR 0003 del catálogo: más segura, pero JHipster no la trae lista y exige distribuir y rotar el par de claves. |
| **Guardar el login como dueño** | El login lo puede cambiar un admin (ver arriba). |
| **Mantener los usuarios por defecto y cambiarles la contraseña** | Es un paso manual que se puede olvidar; mientras tanto, cualquiera que lea el repo entra como admin. |
| **Mantener la recuperación de contraseña con un SMTP** | Es opcional (§3.2) y suma infraestructura y secretos sin aportar a los requisitos obligatorios. |

## Consecuencias

- **Positivas:**
  - Registro, login, hashing (BCrypt) y emisión del token son los de JHipster, con sus tests.
  - El catálogo valida los tokens localmente, sin consultar a turnos.
  - El dueño de cada proceso sale del token (`userId`), nunca de un id enviado por el cliente.
  - Si falta la clave, la app no arranca, en vez de levantar con una clave de ejemplo.
- **Negativas y limitaciones:**
  - **Un token emitido vale hasta que vence** (hasta 24 h, o 30 días con `rememberMe`), aunque
    después el usuario se desactive, pierda un rol o cambie su login: no hay lista de revocación.
    Como el dueño se identifica por `userId`, un cambio de login no le da acceso a nadie a datos
    ajenos, pero el `sub` del token viejo queda desactualizado.
  - Con HS512, cualquiera que tenga la clave (también el catálogo) puede fabricar tokens válidos de
    cualquier usuario y rol. Rotarla exige cambiarla a la vez en los dos servicios.
  - Un usuario creado por un admin en `/api/admin/users` recibe una contraseña aleatoria y, sin
    recuperación por correo, no puede iniciar sesión. El alta normal es el registro.
  - El claim `auth` incluye, además de los roles, `FACTOR_PASSWORD`: Spring Security 7 lo agrega
    al autenticar con contraseña (soporte de autenticación multifactor). No es un rol y no da
    permisos.
- **Seguridad:**
  - La clave vive solo en el `.env` de cada servicio: nunca en el código, en los `application-*.yml`
    versionados, en los tests, en los logs ni en issues o PRs. La de `.yo-rc.json`, del perfil de
    test y de `src/main/docker/jhipster-control-center.yml` es la clave de test, pública a
    propósito. Si la real se filtra, hay que generar una nueva y reemplazarla en los dos servicios.
  - `/management`: solo `health` e `info` son públicos; el resto exige `ROLE_ADMIN`.
