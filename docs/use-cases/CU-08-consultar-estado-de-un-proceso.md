# CU-08: Consultar el estado de un proceso de reserva

## Objetivo

Que la app sepa en qué estado está un proceso de reserva propio: si la cátedra ya pidió el
teléfono, si fue rechazado y por qué, y cuál fue el resultado final. Es el mecanismo por el que la
app se entera de lo que llegó por Kafka.

## Actores

- **Principal:** Usuario final, desde la app KMP.

## Disparador

- La app consulta cada 2 segundos mientras la pantalla del proceso está abierta (DPT-04).
- La app consulta en el momento al volver a esa pantalla.

## Precondiciones

- La app envía un JWT de usuario válido (CU-02).
- La app conoce el identificador local del proceso (lo recibió en CU-04 o desde CU-09).

## Flujo principal

1. La app envía el identificador local del proceso.
2. Turnos valida el token y busca el proceso por su identificador **y** por el `id` del usuario
   autenticado como dueño (DPT-21).
3. Turnos responde con los datos del proceso guardados localmente:
   - estado (DPT-05) y `expiresAt`;
   - profesional, fecha, hora de inicio y hora de fin del turno;
   - datos descriptivos históricos del profesional (DPT-12);
   - en `PHONE_REQUESTED`, el mensaje de la cátedra;
   - en `PHONE_REJECTED`, el motivo y el mensaje del rechazo;
   - en `CONFIRMED` y `CANCELLED`, los datos de confirmación o cancelación;
   - en `EXPIRED`, `INVALID` y `FAILED`, el motivo.

   No se exponen datos técnicos de la integración (`groupId`, JWT técnico).
4. La app actúa según el estado (DPT-04):
   - en `PHONE_REQUESTED` muestra el formulario de teléfono (CU-06);
   - en `PHONE_REJECTED` lo vuelve a mostrar con el motivo;
   - en los estados intermedios muestra la cuenta regresiva hasta `expiresAt`;
   - en `CONFIRMED` o en un estado final muestra el resultado y deja de consultar.

## Flujos alternativos

- **2a. Sin token, token inválido o vencido:** `401`.
- **2b. El proceso no existe o es de otro usuario:** `404`, sin revelar si existe (DPT-19).
- **4a. Pasó `expiresAt` + 1 minuto y el proceso sigue en un estado intermedio:** la app deja de
  consultar y avisa al usuario que revise "Mis reservas" (CU-09). El cierre del proceso lo hace
  CU-11 (DPT-04, DPT-09).

## Postcondiciones

- **De éxito:** la app muestra el estado actual del proceso. La consulta no modifica nada.
- **De fallo:** la app recibe un error y no hay efectos.

## Decisiones del proyecto

- **DPT-04 (cómo se entera la app):** consulta periódica cada 2 segundos, solo con la pantalla del
  proceso abierta; al volver consulta en el momento. Deja de consultar en un estado final o al
  pasar `expiresAt` + 1 minuto sin novedades. Un rechazo no es final. Mientras espera, muestra la
  cuenta regresiva hasta `expiresAt`. Motivo: es REST con la misma autenticación y autorización
  por dueño, no suma infraestructura y la misma consulta sirve para recuperar el estado si la app
  se cierra.
- **DPT-12 (datos históricos):** los datos del profesional se muestran desde el proceso, así que
  la consulta no depende del catálogo.
- **DPT-19 (recursos ajenos):** un proceso de otro usuario responde `404`.
- **DPT-21 (dueño):** el proceso se busca por el `id` del usuario autenticado.

## Respaldo en las consignas

- Enunciado §5 punto 12: reflejar localmente el resultado final del proceso.
- Enunciado §7: paso 7, actualizar el estado local y permitir consultar el resultado.
- Enunciado §8: seguir atendiendo las operaciones que dependen de datos propios.
- Enunciado §9: impedir que un usuario consulte procesos ajenos.
- Contrato §17: la app recibe de turnos el pedido de teléfono y el resultado.
