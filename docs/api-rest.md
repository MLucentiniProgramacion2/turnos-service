# Contrato REST de turnos con la app (DPT-18)

Lo que la app KMP puede pedirle al servicio de turnos y qué recibe. Registro, login y cuenta son los
de JHipster ([ADR 0003](adr/0003-emision-jwt-y-usuarios.md)); acá están los endpoints de la reserva.
Cada sección se completa con el issue que implementa su caso de uso.

| Endpoint | Caso de uso | Estado |
| --- | --- | --- |
| `GET /api/professionals/{professionalId}/availability` | [CU-03](use-cases/CU-03-consultar-turnos-disponibles.md) | implementado (#12) |
| `POST /api/reservation-processes` | [CU-04](use-cases/CU-04-iniciar-reserva.md) | implementado (#12) |
| `POST /api/reservation-processes/{id}/phone` | [CU-06](use-cases/CU-06-informar-telefono.md) | #12 |

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

Lo devuelven CU-04 y CU-06 (y lo va a devolver CU-08). Los bloques que no corresponden al estado no
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
| `confirmation` | en `CONFIRMED` (y `CANCELLED`) | `confirmedAt` |
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
| 400 | `VALIDATION_ERROR` | Falta un campo o tiene otro formato | nada |
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
