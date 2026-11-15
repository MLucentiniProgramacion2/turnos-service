# Contrato REST de turnos con la app (DPT-18)

Lo que la app KMP puede pedirle al servicio de turnos y qué recibe. Registro, login y cuenta son los
de JHipster ([ADR 0003](adr/0003-emision-jwt-y-usuarios.md)); acá están los endpoints de la reserva.
Cada sección se completa con el issue que implementa su caso de uso.

| Endpoint | Caso de uso | Estado |
| --- | --- | --- |
| `GET /api/professionals/{professionalId}/availability` | [CU-03](use-cases/CU-03-consultar-turnos-disponibles.md) | implementado (#12) |
| `POST /api/reservation-processes` | [CU-04](use-cases/CU-04-iniciar-reserva.md) | #12 |
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
