# CU-09: Consultar mis reservas

## Objetivo

Que el usuario vea **solo sus** procesos y reservas (en curso, confirmadas, canceladas y fallidas),
con el estado autoritativo de la cátedra cuando está disponible, sin ver nunca reservas de otros
usuarios de la misma cuenta técnica.

## Actores

- **Principal:** Usuario final, desde la app KMP.
- **Secundario:** API REST de la cátedra (`GET /api/appointments`).

## Disparador

El usuario abre "Mis reservas" en la app, o la app lo sugiere porque un proceso quedó sin
resolver (DPT-04).

## Precondiciones

- La app envía un JWT de usuario válido (CU-02).

## Flujo principal

1. La app pide la lista, con filtro opcional por estado y con paginación.
2. Turnos valida el token y obtiene el `id` del usuario (DPT-21).
3. Turnos lee de la tabla local los procesos cuyo dueño es ese `id`, aplicando el filtro y la
   página pedidos. **Esto define qué se muestra.**
4. Para los procesos de la página que tienen `reservationProcessId`, turnos pide
   `GET /api/appointments?externalPatientId=user-{id}&from=…&to=…` con el rango de fechas de turno
   que cubren. Recorre **todas las páginas** de la respuesta (`X-Total-Count` y `Link`).
5. Turnos cruza las reservas de la cátedra con los procesos locales por `reservationProcessId`.
   Las reservas de la cátedra sin proceso local se ignoran.
6. Si la cátedra informa un estado más avanzado que el local, turnos aplica la transición con las
   reglas de la máquina de estados (DPT-05) y lo registra en el log:
   - local intermedio y cátedra `CONFIRMED` → `CONFIRMED`;
   - local `CONFIRMED` o intermedio y cátedra `CANCELLED` → `CANCELLED`.

   Nunca se retrocede.
7. Turnos responde con la lista paginada. Por cada proceso incluye:
   - estado y datos del turno;
   - datos históricos del profesional (DPT-12);
   - cuando hay reserva en la cátedra, los datos que informa (`confirmedAt`, `cancelledAt`,
     `cancellationReason`).

## Flujos alternativos

- **2a. Sin token, token inválido o vencido:** `401`.
- **3a. El usuario no tiene procesos (o ninguno cumple el filtro):** lista vacía, sin llamar a la
  cátedra.
- **4a. Ningún proceso de la página tiene `reservationProcessId`** (por ejemplo, todos quedaron en
  `FAILED` al crear el hold): no se llama a la cátedra.
- **4b. La cátedra no responde, responde 5xx o falla cualquier página:** **sin reintentos**,
  porque el usuario está esperando. Se responde `200` con los datos locales y una marca de "no
  actualizado". La consulta depende de datos propios, así que sigue funcionando (enunciado §8,
  DPT-23).
- **4c. La cátedra responde `401`, `403 STUDENT_INTEGRATION_NOT_FOUND` o `400`:** se registra en el
  log y se responde como en 4b, sin exponer el motivo técnico (DPT-18).
- **5a. Un proceso local con `reservationProcessId` no aparece en la cátedra:** se muestra con el
  estado local. Si corresponde cerrarlo, lo hace CU-11 (DPT-09).
- **6a. La cátedra informa `FAILED` o `PHONE_PENDING`:** no se cambia el estado local en esta
  consulta. El cierre por vencimiento lo hace CU-11, con su margen sobre `expiresAt` (DPT-09).
- **6b. La transición choca con otro cambio concurrente** (un evento Kafka o CU-11 moviendo el
  mismo proceso): el bloqueo optimista hace que gane uno solo. El estado se vuelve a leer y se
  responde con el actual (DPT-09).

## Postcondiciones

- **De éxito:** el usuario ve solo sus procesos, con el estado de la cátedra cuando estuvo
  disponible. Los procesos que la cátedra mostraba más avanzados quedaron actualizados localmente.
- **De fallo:** el usuario ve los datos locales marcados como "no actualizado", o un error de
  autenticación. Nunca se muestran reservas de otro usuario.

## Decisiones del proyecto

- **DPT-01 y DPT-21 (propiedad):** la lista sale de la tabla local filtrada por el `id` del
  usuario. `externalPatientId` es solo un filtro auxiliar hacia la cátedra: los IDs se repiten si
  se resetea la base local y la cátedra conserva las reservas viejas, así que una reserva de la
  cátedra sin proceso local no se muestra a nadie.
- **DPT-05 (máquina de estados):** el cruce con la cátedra solo aplica transiciones hacia
  adelante.
- **DPT-08 y DPT-23 (fuente y reintentos):** procesos locales más el estado de la cátedra,
  recorriendo todas las páginas de `GET /api/appointments`; sin reintentos; si la cátedra falla,
  datos locales con la marca de "no actualizado".
- **DPT-12 (datos históricos):** los datos del profesional salen del proceso, así que la consulta
  no depende del catálogo.
- **DPT-18 (errores):** los errores de configuración de la cuenta técnica no se reenvían a la app.

## Respaldo en las consignas

- Enunciado §4.2: consultar las reservas realizadas mediante la cuenta técnica del proyecto;
  propiedad por usuario aunque la cátedra identifique por `groupId`.
- Enunciado §5 punto 13: cada usuario consulta sus propias reservas.
- Enunciado §7: REST y Kafka pueden llegar en distinto orden y hay que converger sin retroceder.
- Enunciado §8: seguir atendiendo lo que depende de datos propios; interrupción de la conexión con
  la cátedra.
- Enunciado §9: impedir que un usuario consulte reservas ajenas.
- Contrato §11: `GET /api/appointments`, filtros, paginación con `X-Total-Count` y `Link`;
  devuelve reservas de toda la cuenta técnica.
- Contrato §13.1: `STUDENT_INTEGRATION_NOT_FOUND`, `INVALID_DATE_RANGE`.
