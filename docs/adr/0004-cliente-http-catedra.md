# ADR 0004: Cliente HTTP de la cátedra en turnos: timeouts cortos y operaciones con efectos

- **Estado:** aceptada
- **Fecha:** 2026-11-11
- **Relacionada con:** ADR 0004 del catálogo (`../catalogo-service/docs/adr/0004-cliente-http-catedra.md`),
  [DPT-06, DPT-07 y DPT-08](../use-cases/README.md#decisiones-del-proyecto),
  [CU-03](../use-cases/CU-03-consultar-turnos-disponibles.md),
  [CU-04](../use-cases/CU-04-iniciar-reserva.md) y [CU-11](../use-cases/CU-11-reconciliar-procesos-pendientes.md)

## Contexto

Turnos llama a la API REST de la cátedra para las ocupaciones (contrato §8), para crear y confirmar
holds (§9 y §10) y, más adelante, para listar y cancelar reservas (§11 y §12). El catálogo ya eligió
cómo hacer esos pedidos en su ADR 0004, para el snapshot. **Esta decisión adopta la suya y explica
solo lo que cambia.** Turnos tiene dos diferencias:

- **Las llamadas son cortas y el usuario está esperando:** ocupaciones (CU-03), hold y confirm
  (CU-04). DPT-08 fija 2 s para conectar y 5 s para la respuesta.
- **Hay operaciones con efectos.** Crear un hold reserva un turno; confirmarlo crea una reserva
  `PHONE_PENDING`. El contrato §18.4 dice que un timeout no prueba que la operación falló, y DPT-06
  y DPT-07 deciden qué hacer cuando no se sabe.

## Decisión

### Lo que se adopta del catálogo, sin cambios

- `RestClient` sobre `JdkClientHttpRequestFactory`, fijada a mano; sin redirecciones; HTTP/1.1.
- El JWT técnico como cabecera por defecto del `RestClient` (los adaptadores no lo ven).
- Configuración en `catedra.api.*` (`CatedraApiProperties`), con `CATEDRA_API_URL` y `CATEDRA_JWT`
  sin valor por defecto, y un `toString()` que oculta el token.
- El cuerpo se lee completo dentro de `exchange` y se parsea aparte, con un `JsonMapper` propio
  (`ACCEPT_FLOAT_AS_INT` apagado). DTO con tipos envoltorio y fechas como texto.
- Un solo intento por llamada: los reintentos son del caso de uso.
- Se decide por `status` y `code`, nunca por `detail`.

### Timeouts (DPT-08)

| Propiedad | Variable | Por defecto | Catálogo |
| --- | --- | --- | --- |
| `connect-timeout` | `CATEDRA_CONNECT_TIMEOUT` | `2s` | `5s` |
| `read-timeout` | `CATEDRA_READ_TIMEOUT` | `5s` | `30s` |

El *read timeout* es un plazo total, cuerpo incluido (como explica el ADR del catálogo). Las
respuestas de estas operaciones son de unos cientos de bytes, así que 5 s alcanzan; el catálogo
necesita 30 s porque el snapshot es grande. Las ocupaciones reales tardaron de 0,43 a 0,72 s
(11/11).

### Clasificación de la falla

Una jerarquía `sealed` en el puerto (`reservation.domain.ports.out.CatedraException`), para que los
casos de uso decidan con un `switch` completo:

| Situación | Consulta (`GET`) | Operación con efectos (`POST`) |
| --- | --- | --- |
| Conexión rechazada o timeout de conexión | `CatedraUnavailableException` | `CatedraUnavailableException` (**no se aplicó**) |
| Timeout o corte después de conectar | `CatedraUnavailableException` | `CatedraOutcomeUnknownException` |
| `408`, `429`, `5xx` | `CatedraUnavailableException` | `CatedraOutcomeUnknownException` |
| `2xx` esperado pero ilegible, o `2xx` distinto del esperado | `CatedraInvalidResponseException` | `CatedraOutcomeUnknownException` |
| `401`, `403` sin `code`, `403 STUDENT_INTEGRATION_NOT_FOUND` | `CatedraAccessDeniedException` | `CatedraAccessDeniedException` |
| Otro `4xx` con `code` (incluido `403 HOLD_OWNER_MISMATCH`) | `CatedraRejectedException(status, code)` | ídem |
| `3xx`, `4xx` sin `code`, otro status | `CatedraInvalidResponseException` | ídem |

- El `code` es un enum del puerto (`CatedraErrorCode`) con los de §13.1 para ocupaciones, holds,
  listado y cancelación, más `UNRECOGNIZED`. El texto original va al mensaje solo si tiene la forma
  `[A-Z_]{1,64}`.
- `409 RESERVATION_PROCESS_ALREADY_CONFIRMED` llega como rechazo: que es un éxito lo decide el caso
  de uso (CU-04 11d).

### Qué se puede distinguir de verdad (medido el 11/11)

Con `RestClient` sobre el cliente del JDK 25 contra sockets propios en loopback:

| Caso | Excepción (causa de `ResourceAccessException`) |
| --- | --- |
| Puerto cerrado | `ConnectException` |
| Cola de conexiones llena | `HttpConnectTimeoutException` |
| El servidor acepta y no lee el cuerpo | `HttpTimeoutException("Request cancelled")` |
| El servidor lee todo y no responde | **la misma** `HttpTimeoutException` |
| El servidor lee y cierra | `IOException("header parser received no bytes")` |
| `201` con medio cuerpo | `IOException("fixed content-length…")` |

**Solo antes de conectar se sabe que el pedido no salió.** Desde que hay conexión, un timeout es el
mismo haya leído el servidor el pedido o no. Por eso, en un `POST`, todo lo que pasa después de
conectar es "resultado desconocido". `HttpConnectTimeoutException` es subclase de
`HttpTimeoutException`: hay que preguntar por ella explícitamente.

### Reintento automático del JDK en los `GET`

Medido: si el servidor cierra la conexión sin responder, el cliente HTTP del JDK **repite el `GET`
una vez por su cuenta** (llegan dos pedidos). Con un `POST` no lo hace (llega uno). No se desactiva:

- solo afecta a consultas sin efectos (las ocupaciones, y el listado de reservas más adelante);
- desactivarlo exige una propiedad de sistema que vale para toda la JVM.

Lo fija un test (`CatedraOccupancyClientTest`), para que un cambio de JDK se note. La consecuencia
es que "un solo intento por llamada" vale para los `POST`, pero un `GET` puede salir dos veces. Lo
mismo pasa en el catálogo con el snapshot; no se tocó.

## Alternativas consideradas

Las del ADR 0004 del catálogo (`RestTemplate`, `WebClient`, OpenFeign, otras fábricas) se descartan
por los mismos motivos. Además:

| Alternativa | Motivo para descartarla |
| --- | --- |
| Tratar cualquier falla de un `POST` como "no se aplicó" | Es justo lo que prohíbe el §18.4. Un hold creado y no registrado bloquea el turno; un confirm aplicado y tratado como fallido deja una reserva `PHONE_PENDING` que nadie completa. |
| Tratar cualquier falla de un `POST` como "resultado desconocido", también sin conexión | Perdería información útil: con la cátedra caída, el caso de uso puede responder enseguida sin dejar nada pendiente de reconciliar. |
| Un resultado `sealed` en vez de excepciones | El catálogo ya usa excepciones `sealed` en sus puertos (#19). Los rechazos son resultados esperados, pero mantener un solo estilo pesa más. |
| Reintentos dentro del adaptador | Los decide el caso de uso: sin reintentos cuando el usuario espera, hasta 3 en segundo plano y nunca el `POST` del hold (DPT-08). |

## Consecuencias

- **Positivas:**
  - CU-04 y CU-11 distinguen "no se aplicó" de "resultado desconocido" por tipo, con un `switch`
    que el compilador obliga a completar.
  - Los tests usan un servidor HTTP real del JDK (`FakeHttpServer`): timeouts, cortes, cierre sin
    respuesta, puerto cerrado y, en Linux, timeout de conexión.
- **Negativas:**
  - Con la cátedra lenta, el usuario espera hasta 7 s (2 + 5) por llamada.
  - El test del timeout de conexión depende de cómo descarta Linux las conexiones con la cola llena.
    Solo corre en Linux (el CI es Linux).
- **Seguridad:** igual que en el catálogo. El token no aparece en `toString()`, en los mensajes de
  excepción ni en `/management/env` y `/management/configprops`. **Nunca activar
  `-Djdk.httpclient.HttpClient.log=headers`** con el token real.
