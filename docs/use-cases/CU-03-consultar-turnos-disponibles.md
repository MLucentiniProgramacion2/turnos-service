# CU-03: Consultar turnos disponibles

## Objetivo

Que el usuario vea los turnos libres de un profesional en una fecha o en un rango de hasta 7 días,
combinando la **agenda vigente** que informa el catálogo con las **ocupaciones** que informa la
cátedra.

## Actores

- **Principal:** Usuario final, desde la app KMP.
- **Secundarios:** Servicio de catálogo (CU-07 del catálogo), API REST de la cátedra
  (ocupaciones).

## Disparador

El usuario eligió un profesional (desde la búsqueda del catálogo, CU-05 del catálogo) y pide ver
sus turnos para una fecha o una semana.

## Precondiciones

- La app envía un JWT de usuario válido (CU-02).

## Flujo principal

1. La app envía el ID del profesional y el rango `from`–`to`. Si pide un solo día, `from = to`.
2. Turnos valida el token y el rango (DPT-10):
   - `from` no es posterior a `to`;
   - el rango tiene como máximo 7 días;
   - `from` no es anterior a hoy y `to` no supera hoy + 60 días. "Hoy" se calcula en la zona
     horaria de atención configurada.
3. Turnos pide al catálogo la agenda vigente del profesional (CU-07 del catálogo) con su token
   técnico de servicio y el login del usuario en `X-User-Login` (DPT-16).
4. El catálogo responde con el profesional (incluido su estado habilitado), su categoría y sus
   horarios semanales habilitados (`dayOfWeek`, `startTime`, `endTime`, `slotDurationMinutes`).
5. Turnos genera los slots candidatos: para cada fecha del rango y cada horario habilitado de ese
   día de la semana, un slot cada `slotDurationMinutes` desde `startTime`, siempre que el slot
   termine antes o justo en `endTime`.
6. Turnos pide a la cátedra `GET /api/appointment-occupancies?professionalId=…&from=…&to=…` con el
   JWT técnico de la cátedra.
7. Turnos descarta los slots que figuran en las ocupaciones, tanto `CONFIRMED` como `HELD`.
8. Si el rango incluye hoy, turnos descarta los slots cuyo inicio ya pasó.
9. Turnos responde con los slots libres agrupados por fecha (fecha, hora de inicio y hora de fin) y
   los datos del profesional.

## Flujos alternativos

- **2a. Sin token, token inválido o vencido:** `401`.
- **2b. Rango inválido** (`from` posterior a `to`, más de 7 días, fecha pasada o fuera del
  horizonte): `400` en `problem+json`. No se llama al catálogo ni a la cátedra (DPT-10).
- **3a. El catálogo no responde, responde 5xx o responde `503` por base vacía** (DP-03 del
  catálogo): `503`, sin reintentos (DPT-11 y DPT-13). No se llama a la cátedra.
- **4a. El profesional no existe en el catálogo:** `404`.
- **4b. El profesional está deshabilitado:** `400`, indicando que el profesional no está
  habilitado. No se ofrecen turnos.
- **5a. El profesional no tiene horarios habilitados en el rango:** lista vacía. Es una respuesta
  válida y no hace falta llamar a la cátedra.
- **6a. La cátedra no responde o responde 5xx:** hasta 3 reintentos con backoff de 1 s, 2 s y 4 s
  (DPT-08). Si se agotan, `503`.
- **6b. La cátedra responde `400 INVALID_DATE_RANGE`:** no debería pasar, porque el paso 2 ya
  validó el rango. Se registra en el log y se responde `500`.
- **6c. La cátedra responde `401` o `403`** (JWT técnico vencido o cuenta técnica mal
  configurada; Spring Security puede responder sin `code`, contrato §13): se registra en el log y
  se responde `503` genérico, sin exponer el motivo técnico (DPT-18).
- **7a. Todos los slots están ocupados:** lista vacía. Es una respuesta válida.

## Postcondiciones

- **De éxito:** el usuario ve los turnos libres en el momento de la consulta. Turnos **no guarda**
  la agenda ni las ocupaciones: la disponibilidad se calcula en cada consulta.
- **De fallo:** el usuario recibe un error `problem+json` y no hay efectos sobre el estado del
  servicio.

## Decisiones del proyecto

- **DPT-10 (rango de fechas):** hasta 7 días por consulta, resueltos con una sola llamada de
  ocupaciones; horizonte desde hoy hasta 60 días adelante (configurable); sin fechas pasadas; en el
  día de hoy se descartan los slots ya iniciados. La zona horaria de atención es configurable por
  variable de entorno, por defecto `America/Argentina/Mendoza`. Un rango inválido se rechaza con
  `400` antes de llamar a la cátedra.
- **DPT-11 (catálogo no disponible):** sin agenda vigente no se calcula disponibilidad: `503`.
- **DPT-13 (llamadas al catálogo):** timeouts de 2 s de conexión y 5 s de lectura, sin
  reintentos.
- **DPT-08 (llamadas a la cátedra):** timeouts de 2 s y 5 s, hasta 3 reintentos con backoff de
  1 s, 2 s y 4 s. Este GET es seguro de repetir.
- **DPT-16 (autenticación con el catálogo):** token técnico de servicio con `ROLE_SERVICE`,
  `aud=catalogo-service` e `iss=turnos-service`, y el login del usuario en `X-User-Login` solo para
  trazabilidad. El JWT del usuario no se reenvía.
- **DPT-18 (errores):** los nombres de los `code` propios se fijan en el contrato REST con KMP.

## Notas

- Un slot ocupado por un hold propio del usuario también aparece como no disponible.
- La disponibilidad es una foto del momento: entre la consulta y la reserva otro cliente puede
  tomar el slot. Por eso la cátedra vuelve a validar al crear el hold (CU-04).
- Coherencia con el catálogo: el filtro "disponibilidad" de la búsqueda del catálogo solo mira si
  el profesional atiende en un día o una franja (DP-01 del catálogo). Los turnos libres reales se
  calculan acá.

## Respaldo en las consignas

- Enunciado §4.2: turnos construye la disponibilidad a partir de horarios y ocupaciones y obtiene
  del catálogo la información vigente.
- Enunciado §5 punto 8: construir y presentar turnos disponibles.
- Enunciado §7: combinar reglas semanales vigentes, ocupaciones y la fecha y el profesional
  elegidos.
- Enunciado §8: con el catálogo no disponible no se inician operaciones que requieran información
  vigente; timeouts y reintentos limitados.
- Contrato §8: `GET /api/appointment-occupancies`, estados `CONFIRMED` y `HELD`; una lista vacía es
  válida.
- Contrato §13 y §13.1: `INVALID_DATE_RANGE`, `503`, rechazos `401`/`403` sin `code`.
- Contrato §17: diagrama de reserva (agenda vigente del catálogo y ocupaciones de la cátedra).
