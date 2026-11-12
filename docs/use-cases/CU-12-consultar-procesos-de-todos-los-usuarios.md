# CU-12: Consultar procesos y reservas de todos los usuarios

## Objetivo

Que un administrador consulte, **en modo solo lectura**, los procesos y reservas de cualquier
usuario, para diagnóstico (por ejemplo, encontrar procesos trabados) y para demostrar la
autorización por rol.

## Actores

- **Principal:** Administrador (usuario con `ROLE_ADMIN`).

## Disparador

El administrador consulta el listado o el detalle desde un cliente con su JWT, por ejemplo desde
Postman o la app.

## Precondiciones

- El administrador envía un JWT de usuario válido (CU-02) con `ROLE_ADMIN`.

## Flujo principal

1. El administrador pide el listado bajo `/api/admin/...`, con filtros opcionales por usuario y
   por estado, y con paginación.
2. Turnos valida el token y verifica `ROLE_ADMIN`.
3. Turnos lee los procesos de la tabla local que cumplen los filtros, de todos los usuarios.
4. Turnos responde con la lista paginada. Por cada proceso incluye:
   - el dueño (`id` y login);
   - estado, turno y datos históricos del profesional;
   - los identificadores de integración útiles para diagnóstico (`holdId`, `reservationProcessId`,
     `requestEventId`, último `eventId` enviado, `expiresAt`).
5. Opcionalmente, el administrador pide el detalle de un proceso por su identificador local y
   recibe los mismos datos de ese proceso.

## Flujos alternativos

- **2a. Sin token, token inválido o vencido:** `401`.
- **2b. Token de un usuario sin `ROLE_ADMIN`:** `403`. Un usuario común nunca accede a estos
  endpoints.
- **3a. Ningún proceso cumple los filtros:** lista vacía.
- **5a. El proceso del detalle no existe:** `404`.

## Postcondiciones

- **De éxito:** el administrador ve los procesos pedidos. **No se modifica nada**, ni en turnos ni
  en la cátedra.
- **De fallo:** se responde un error y no hay efectos.

## Decisiones del proyecto

- **DPT-14 (rol administrador, solo consulta):** el rol opcional del enunciado se implementa en
  modo solo lectura: no cancela ni modifica. El acceso ampliado existe únicamente en los endpoints
  `/api/admin/...`. Un administrador que usa los endpoints comunes (CU-08, CU-09, CU-10) ve y
  opera solo lo suyo, así que el rol no debilita el aislamiento entre usuarios comunes.
- **DPT-23:** este caso usa **solo datos locales**; no consulta a la cátedra.
- **DPT-21 (dueño):** el filtro por usuario y el dueño mostrado se basan en el `id` interno; el
  login se muestra como dato descriptivo.

## Notas

- Nunca se exponen el JWT técnico de la cátedra ni otros secretos de la integración.

## Respaldo en las consignas

- Enunciado §9: rol administrador opcional para consultar o gestionar reservas de otros usuarios,
  con permisos documentados y operaciones correctamente autorizadas, sin reemplazar el aislamiento
  obligatorio entre usuarios comunes.
- Enunciado §11: evidencia de rechazo de accesos no autorizados.
