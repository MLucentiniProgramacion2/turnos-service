# Contrato REST de turnos con la app (DPT-18)

Lo que la app KMP puede pedirle al servicio de turnos y qué recibe. Registro, login y cuenta son los
de JHipster ([ADR 0003](adr/0003-emision-jwt-y-usuarios.md)); acá están los endpoints de la reserva.
Cada sección se completa con el issue que implementa su caso de uso.

| Endpoint | Caso de uso | Estado |
| --- | --- | --- |
| `GET /api/professionals/{professionalId}/availability` | [CU-03](use-cases/CU-03-consultar-turnos-disponibles.md) | implementado (#12) |
| `POST /api/reservation-processes` | [CU-04](use-cases/CU-04-iniciar-reserva.md) | implementado (#12) |
| `POST /api/reservation-processes/{id}/phone` | [CU-06](use-cases/CU-06-informar-telefono.md) | implementado (#12) |
| `GET /api/reservation-processes/{id}` | [CU-08](use-cases/CU-08-consultar-estado-de-un-proceso.md) | implementado (#14) |
| `GET /api/reservation-processes` | [CU-09](use-cases/CU-09-consultar-mis-reservas.md) | implementado (#14) |
| `POST /api/reservation-processes/{id}/cancel` | [CU-10](use-cases/CU-10-cancelar-reserva-propia.md) | implementado (#14) |

## Convenciones

- **Autenticación:** `Authorization: Bearer <JWT de usuario>` (el de `POST /api/authenticate`). Sin
  token, con uno inválido o vencido: `401`. Con el token técnico de servicio o sin `ROLE_USER` ni
  `ROLE_ADMIN`: `403`. Los dos sin cuerpo (los responde Spring Security).
- **La identidad sale del token.** Ningún endpoint recibe un ID de usuario.
- **Formatos:** fechas `yyyy-MM-dd`; instantes ISO-8601 en UTC; horas locales de la zona de atención,
  siempre como `HH:mm:ss` en las respuestas; JSON en camelCase.
- **Errores:** `application/problem+json`, con el formato de JHipster (`type`, `title`, `status`,
  `detail`, `message`, `path`) más un `code` propio. **La app decide por `status` y `code`**, nunca
  por `detail`, que es un texto fijo para mostrar o registrar.
- **Lo que un error nunca trae:** el valor que mandó el cliente (salvo en `path`, que es la URI del
  pedido), el `detail` de la cátedra, nombres de clases, ni el motivo técnico de una falla de
  integración (DPT-18).

### Errores comunes a todos los endpoints

| HTTP | `code` | Cuándo |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | Falta un campo o tiene otro formato. Con `fieldErrors` (`objectName`, `field`, `message`) cuando se pudo leer el pedido; sin `fieldErrors` si el cuerpo no es un JSON legible |
| 400 | (sin `code`) | Una variable de la ruta que no se puede convertir, como un `professionalId` que no es un número. El `detail` nombra el parámetro (`Failed to convert 'professionalId'`) |
| 401, 403 | (sin cuerpo) | Ver "Autenticación" |
| 500 | `INTERNAL_ERROR` | Error propio de turnos. Queda en el log |
| 502 | `UPSTREAM_INVALID_RESPONSE` | El catálogo o la cátedra respondieron algo fuera de su contrato |
| 503 | `CATALOG_UNAVAILABLE` | El servicio de catálogo no está disponible. Con `Retry-After` (segundos) si el catálogo informó cuándo reintentar |
| 503 | `CATEDRA_UNAVAILABLE` | La cátedra no está disponible |

Un problema de configuración de las credenciales técnicas (el token de servicio o el JWT de la
cátedra) se responde con el mismo `503` que una caída, sin `Retry-After`: la app no puede hacer nada
distinto y el motivo no se le muestra al usuario. Queda como ERROR en el log.

## CU-03: turnos disponibles

```http
GET /api/professionals/{professionalId}/availability?from=2026-12-14&to=2026-12-15
Authorization: Bearer <JWT de usuario>
```

| Parámetro | Tipo | Obligatorio | Regla |
| --- | --- | --- | --- |
| `professionalId` | entero | sí | El `id` del profesional en el catálogo |
| `from` | fecha | sí | Inicio, incluido. No puede ser anterior a hoy |
| `to` | fecha | sí | Fin, incluido. `to >= from`, `to <= from + 6` (7 días) y `to <= hoy + 60` |

"Hoy" se calcula en la zona de atención (`America/Argentina/Mendoza` por defecto). Para un solo día,
`from = to`.

`200 OK`:

```json
{
  "professional": {
    "id": 202,
    "firstName": "Ana",
    "lastName": "Gomez",
    "category": { "id": 1, "name": "Clinica medica", "enabled": true }
  },
  "from": "2026-12-14",
  "to": "2026-12-15",
  "timeZone": "America/Argentina/Mendoza",
  "days": [
    { "date": "2026-12-14", "slots": [] },
    {
      "date": "2026-12-15",
      "slots": [
        { "startTime": "09:00:00", "endTime": "09:30:00" },
        { "startTime": "10:30:00", "endTime": "11:00:00" }
      ]
    }
  ]
}
```

- `days` trae **todos** los días del rango, en orden, también los que no tienen turnos (`slots: []`):
  porque el profesional no atiende ese día, porque está todo ocupado o porque los turnos de hoy ya
  empezaron.
- `slots` va ordenado por `startTime`. Un turno se identifica por profesional, fecha y `startTime`:
  es lo que se manda para reservarlo (CU-04).
- No aparecen los turnos ocupados en la cátedra (confirmados o retenidos por un hold, también uno
  propio) ni, en el día de hoy, los que ya empezaron.
- `category.enabled` se informa como en el catálogo: una categoría deshabilitada no cambia los turnos.
- Es una foto del momento: la cátedra vuelve a validar al reservar.

Errores propios de este endpoint:

| HTTP | `code` | Cuándo |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | Falta `from` o `to`, o no es una fecha `yyyy-MM-dd` |
| 400 | `INVALID_DATE_RANGE` | `from` posterior a `to`, más de 7 días, `from` anterior a hoy o `to` posterior a hoy + 60. No se consulta al catálogo ni a la cátedra |
| 400 | `PROFESSIONAL_DISABLED` | El profesional está deshabilitado |
| 404 | `PROFESSIONAL_NOT_FOUND` | El catálogo no conoce a ese profesional (o el `id` no es positivo) |

Más los comunes: `500`, `502` y los dos `503`.

## El proceso de reserva

Lo devuelven CU-04, CU-06, CU-08 y CU-10, y es cada elemento de la lista de CU-09. Los bloques que no corresponden al estado no
se mandan.

```json
{
  "id": "5f0c1c5e-3d0e-4a53-9f43-0b1a8c0b7f11",
  "status": "PHONE_REJECTED",
  "expiresAt": "2026-12-14T12:40:05Z",
  "appointment": { "professionalId": 202, "date": "2026-12-15", "startTime": "10:00:00", "endTime": "10:30:00" },
  "professional": { "firstName": "Ana", "lastName": "Gomez", "categoryName": "Clinica medica", "slotDurationMinutes": 30 },
  "phoneRequest": { "message": "Ingrese un numero de telefono para continuar." },
  "rejection": { "reason": "INVALID_PHONE_NUMBER", "message": "El telefono ingresado no es valido." }
}
```

| Campo | Cuándo viene | Qué es |
| --- | --- | --- |
| `id` | siempre | El identificador local del proceso (UUID). Es el único que usa la app |
| `status` | siempre | `CREATING_HOLD`, `HOLD_CREATED`, `WAITING_FOR_PHONE_REQUEST`, `PHONE_REQUESTED`, `PHONE_SUBMITTED`, `PHONE_REJECTED`, `CONFIRMED`, `CANCELLED`, `EXPIRED`, `INVALID` o `FAILED` (ver la [máquina de estados](use-cases/README.md#máquina-de-estados-del-proceso-de-reserva)) |
| `expiresAt` | desde que hay hold | Hasta cuándo se puede completar el proceso, según la cátedra. Para la cuenta regresiva |
| `appointment` | siempre | El turno |
| `professional` | siempre | Datos del profesional al iniciar la reserva, solo para mostrar |
| `phoneRequest` | desde `PHONE_REQUESTED` | La cátedra pidió el teléfono; `message` es su texto, si lo mandó |
| `rejection` | en `PHONE_REJECTED` (y después) | El último rechazo del teléfono: `reason` y `message` de la cátedra |
| `confirmation` | en `CONFIRMED` (y `CANCELLED`, si se conoce) | `confirmedAt`: cuándo la confirmó la cátedra |
| `cancellation` | en `CANCELLED` | `reason` (puede faltar) y `cancelledAt` |
| `closing` | en `EXPIRED`, `INVALID` y `FAILED` | `reason`: un código (de la cátedra, o propio como `HOLD_OUTCOME_UNKNOWN`), no un texto para mostrar |

**No se exponen** `holdId`, `reservationProcessId` ni `reservationId` de la cátedra, los `eventId`, el
teléfono ni los nombres del paciente: la app no los necesita para ninguna operación.

## CU-04: iniciar una reserva

```http
POST /api/reservation-processes
Authorization: Bearer <JWT de usuario>
Content-Type: application/json

{ "professionalId": 202, "date": "2026-12-15", "startTime": "10:00" }
```

Los tres campos son obligatorios. `startTime` acepta `HH:mm` y `HH:mm:ss`, con los segundos en cero.
El cuerpo no lleva ningún dato del usuario: el dueño sale del token y el nombre del paciente, de su
perfil.

| Respuesta | Cuándo |
| --- | --- |
| `202 Accepted` + proceso | Se inició la reserva. Normalmente en `WAITING_FOR_PHONE_REQUEST`; puede venir en `HOLD_CREATED` (la confirmación inicial quedó pendiente) o más avanzado si la cátedra ya pidió el teléfono |
| `200 OK` + proceso | El usuario ya tenía un proceso en curso o confirmado para **ese mismo turno**: se devuelve ese, sin crear nada. Repetir el pedido (doble toque, respuesta perdida) es seguro, también con el catálogo caído |

Con el `202`, la app abre la pantalla del proceso y consulta su estado (CU-08).

| HTTP | `code` | Cuándo | Qué quedó |
| --- | --- | --- | --- |
| 400 | `VALIDATION_ERROR` | Falta un campo, tiene otro formato o el `professionalId` no es positivo (en el cuerpo lo frena `@Positive`; el `404` por un `id` no positivo es solo de CU-03, donde viene en la ruta) | nada |
| 400 | `INVALID_DATE_RANGE` | La fecha ya pasó o es posterior a hoy + 60 | nada |
| 400 | `INVALID_SLOT` | El turno ya empezó, la hora tiene segundos, o no es un turno de un horario habilitado (según el catálogo o según la cátedra) | nada, o un proceso `FAILED` |
| 400 | `PROFESSIONAL_DISABLED` | El profesional está deshabilitado | nada, o `FAILED` |
| 401 | `USER_NOT_FOUND` | El usuario del token ya no existe | nada |
| 404 | `PROFESSIONAL_NOT_FOUND` | El profesional no existe | nada, o `FAILED` |
| 409 | `RESERVATION_IN_PROGRESS` | El usuario tiene una reserva en curso para **otro** turno. Trae `processId`, el `id` de ese proceso | nada |
| 409 | `PROFILE_INCOMPLETE` | El nombre o el apellido del perfil no tienen de 2 a 100 caracteres | nada |
| 409 | `SLOT_NOT_AVAILABLE` | Otro tomó el turno | `FAILED` |
| 409 | `RESERVATION_EXPIRED` | El hold venció antes de confirmarlo | `EXPIRED` |
| 503 | `HOLD_OUTCOME_UNKNOWN` | La cátedra no respondió al crear el hold: no se sabe si se creó. **El horario puede quedar bloqueado unos minutos**; conviene ofrecer otro turno | `FAILED` |
| 503 | `CATEDRA_UNAVAILABLE` | No se pudo conectar con la cátedra (el hold no se creó) | `FAILED` |
| 503 | `CATALOG_UNAVAILABLE` | Sin catálogo no se inicia una reserva | nada |
| 500 | `INTERNAL_ERROR` | Error propio; si el `detail` avisa que el horario puede quedar bloqueado, el hold se creó y no se pudo registrar | — |

Un proceso `FAILED` o `EXPIRED` es final y no bloquea al usuario: puede volver a reservar.

## CU-06: informar el teléfono

```http
POST /api/reservation-processes/{id}/phone
Authorization: Bearer <JWT de usuario>
Content-Type: application/json

{ "phoneNumber": "+54 9 (261) 555-12.34" }
```

`{id}` es el `id` del proceso (UUID). El teléfono va como lo escribió el usuario: turnos quita
espacios, guiones, paréntesis y puntos, igual que la cátedra, y exige `^\+?[0-9]{7,15}$`. Se puede
enviar cuando el proceso está en `PHONE_REQUESTED`, y otra vez en `PHONE_REJECTED` (después de un
rechazo), mientras no haya pasado `expiresAt`.

`202 Accepted` + proceso: el teléfono se publicó con confirmación del broker. Normalmente en
`PHONE_SUBMITTED`; puede venir más avanzado si el resultado ya llegó. La app sigue consultando el
estado (CU-08) hasta ver el resultado.

| HTTP | `code` | Cuándo | Qué quedó |
| --- | --- | --- | --- |
| 400 | `VALIDATION_ERROR` | Falta `phoneNumber`, no es texto o tiene más de 40 caracteres | nada |
| 400 | `INVALID_PHONE_NUMBER` | El teléfono no tiene el formato que acepta la cátedra. No se repite el valor | nada |
| 400 | (sin `code`) | `{id}` no es un UUID | nada |
| 404 | `RESERVATION_PROCESS_NOT_FOUND` | El proceso no existe **o es de otro usuario** (la respuesta es la misma) | nada |
| 409 | `PHONE_NOT_REQUESTED` | La cátedra todavía no pidió el teléfono | nada |
| 409 | `PHONE_ALREADY_SUBMITTED` | El teléfono ya se envió: hay que esperar el resultado | nada |
| 409 | `RESERVATION_PROCESS_CLOSED` | El proceso está confirmado o terminó. Trae `status`, el estado actual | nada |
| 409 | `RESERVATION_EXPIRED` | Pasó `expiresAt` | nada (el proceso lo cierra el evento de vencimiento) |
| 503 | `PHONE_SUBMISSION_UNCONFIRMED` | Kafka no confirmó el envío | el proceso no cambia. **Se puede volver a enviar**; si el primer envío había llegado, la cátedra procesa los dos y gana el primer resultado |
| 500 | `INTERNAL_ERROR` | El teléfono se publicó pero no se pudo guardar el envío | el proceso avanza cuando llegue el resultado |

## CU-08: el estado de un proceso

```http
GET /api/reservation-processes/{id}
Authorization: Bearer <JWT de usuario>
```

`200 OK` + proceso, con el mismo formato que devuelven CU-04 y CU-06. Es lo que la app consulta cada 2 s con la
pantalla del proceso abierta (DPT-04): así se entera del pedido de teléfono, de un rechazo y del resultado, que llegan
por Kafka.

- Lee solo lo guardado: no llama al catálogo ni a la cátedra, así que responde aunque estén caídos.
- **No modifica nada**, tampoco si pasó `expiresAt`: el proceso lo cierra el evento de la cátedra (CU-07) o la
  reconciliación (CU-11). Si pasó `expiresAt` + 1 minuto y sigue en un estado intermedio, la app deja de consultar y
  manda al usuario a "Mis reservas" (CU-08 4a).
- Un administrador ve acá **solo sus propios procesos** (DPT-14); los de todos están en `/api/admin/...`.

| HTTP | `code` | Cuándo |
| --- | --- | --- |
| 400 | (sin `code`) | `{id}` no es un UUID. El `detail` es fijo; el valor solo aparece en `path`, que es la URI |
| 404 | `RESERVATION_PROCESS_NOT_FOUND` | El proceso no existe **o es de otro usuario** (la respuesta es la misma) |

## CU-09: mis reservas

```http
GET /api/reservation-processes?status=CONFIRMED,CANCELLED&page=0&size=20
Authorization: Bearer <JWT de usuario>
```

| Parámetro | Obligatorio | Regla |
| --- | --- | --- |
| `status` | no | Uno o varios estados del proceso, separados por coma o repitiendo el parámetro (`status=CONFIRMED&status=CANCELLED`). Sin ninguno, todos |
| `page` | no | Desde 0 (por defecto) hasta 10 000 |
| `size` | no | De 1 a 50; por defecto 20 |

`200 OK`, también si la cátedra no respondió:

```json
{
  "page": 0,
  "size": 20,
  "totalElements": 2,
  "totalPages": 1,
  "catedraUpToDate": true,
  "processes": [
    {
      "id": "5f0c1c5e-3d0e-4a53-9f43-0b1a8c0b7f11",
      "status": "CANCELLED",
      "expiresAt": "2026-12-14T12:40:05Z",
      "appointment": { "professionalId": 202, "date": "2026-12-15", "startTime": "10:00:00", "endTime": "10:30:00" },
      "professional": { "firstName": "Ana", "lastName": "Gomez", "categoryName": "Clinica medica", "slotDurationMinutes": 30 },
      "phoneRequest": { "message": "Ingrese un numero de telefono para continuar." },
      "confirmation": { "confirmedAt": "2026-12-14T12:41:00Z" },
      "cancellation": { "reason": "Cambio de planes", "cancelledAt": "2026-12-16T09:00:00Z" }
    }
  ]
}
```

- **Solo los procesos del usuario del token**, de la tabla local (DPT-01, DPT-21). Una reserva que la cátedra tiene para
  la cuenta técnica y no tiene un proceso local no aparece nunca, aunque tenga el mismo `externalPatientId`. Un
  administrador también ve acá solo lo suyo (DPT-14).
- **Orden:** fecha del turno descendente, hora descendente y, a igualdad, el `id`. `totalElements` y `totalPages` son de
  la misma lectura que la página; una página fuera de rango viene vacía con los totales.
- **Cada proceso, con el mismo formato que CU-04, CU-06 y CU-08**, ya **convergido** con la cátedra: lo que la cátedra
  informa (confirmación, cancelación con su motivo y su instante) sale de los bloques `confirmation` y `cancellation` del
  proceso, nunca de la respuesta de la cátedra.
- **Convergencia hacia adelante:** si la cátedra tiene la reserva más avanzada que el proceso local, el proceso se
  actualiza antes de responder (en curso → `CONFIRMED`; en curso o `CONFIRMED` → `CANCELLED`). Nunca se retrocede ni se
  reabre un estado final. Por eso es un `GET` que puede escribir: lo que escribe es lo mismo que traería el evento de
  Kafka, solo avanza y repetirlo no cambia nada.
- **El filtro `status` se aplica antes de converger.** Un proceso pedido como `CONFIRMED` puede venir `CANCELLED` si la
  cátedra lo informa así: es su estado actual. El total no se recalcula.
- **`catedraUpToDate`:** `false` si hacía falta preguntarle a la cátedra y no respondió (o respondió un error): los
  procesos vienen con el estado local, y la app puede mostrar "no se pudo actualizar". `true` si se consultó, y también
  si no hacía falta (no hay procesos, o ninguno puede cambiar: un estado final o un proceso sin hold no cambia por lo que
  diga la cátedra). Un proceso en curso que la cátedra todavía no cerró se muestra tal cual; lo cierra la reconciliación
  (CU-11).

| HTTP | `code` | Cuándo |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | `status` con un valor que no es un estado, o `page` o `size` fuera de rango. No se repite el valor |

La cátedra caída **no** es un error de este endpoint: es `200` con `catedraUpToDate: false`.

## CU-10: cancelar una reserva propia

```http
POST /api/reservation-processes/{id}/cancel
Authorization: Bearer <JWT de usuario>
Content-Type: application/json

{ "reason": "Me surgió un viaje" }
```

El cuerpo es **opcional**, y `reason` también (hasta 500 caracteres, el máximo de la cátedra). Sin motivo, se puede
mandar sin cuerpo, con `{}` o con `reason` en blanco. El motivo se guarda y se le manda a la cátedra; nunca se escribe
en el log.

`200 OK` + proceso en `CANCELLED`, con `cancellation.reason` y `cancellation.cancelledAt` (cuándo la canceló la
cátedra). **Cancelar es idempotente:** si el proceso ya estaba `CANCELLED`, responde `200` con el estado actual sin
volver a pedirle nada a la cátedra. Si `AppointmentCancelled` llegó por Kafka antes de guardar, también `200`, con los
datos del evento.

| HTTP | `code` | Cuándo | Qué quedó |
| --- | --- | --- | --- |
| 400 | `VALIDATION_ERROR` | `reason` tiene más de 500 caracteres. No se repite el valor | nada |
| 400 | (sin `code`) | `{id}` no es un UUID | nada |
| 404 | `RESERVATION_PROCESS_NOT_FOUND` | El proceso no existe **o es de otro usuario** (también para un administrador) | nada |
| 409 | `RESERVATION_NOT_CANCELLABLE` | Solo se cancelan reservas confirmadas. Trae `status`, **el estado local** del proceso. Si viene `CONFIRMED`, es que la cátedra dijo que no se puede cancelar: hay una discrepancia, que corrige "Mis reservas" (CU-09) o la reconciliación (CU-11) | nada |
| 503 | `CATEDRA_UNAVAILABLE` | No se pudo conectar con la cátedra: **seguro que no se canceló**. Se puede reintentar | sigue `CONFIRMED` |
| 503 | `CANCELLATION_OUTCOME_UNKNOWN` | La cátedra no respondió después de recibir el pedido: **puede que se haya cancelado**. Conviene revisar "Mis reservas", o reintentar: es seguro, porque cancelar algo ya cancelado devuelve su estado | sigue `CONFIRMED` localmente; si se canceló en la cátedra, converge por el evento o por CU-09 |
| 502 | `UPSTREAM_INVALID_RESPONSE` | La cátedra respondió fuera de su contrato | sigue `CONFIRMED` |
| 500 | `INTERNAL_ERROR` | La cátedra rechazó la cancelación por un error propio (la reserva no existe en la cátedra, es de otra cuenta técnica), o se canceló y no se pudo guardar | sigue `CONFIRMED` hasta que converja |
