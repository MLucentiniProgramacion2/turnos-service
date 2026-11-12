# ADR 0005: Token técnico de servicio hacia el catálogo y rechazo de ROLE_SERVICE en turnos

- **Estado:** aceptada
- **Fecha:** 2026-11-11
- **Relacionada con:** [DPT-16](../use-cases/README.md#decisiones-del-proyecto),
  [ADR 0003 (emisión del JWT)](0003-emision-jwt-y-usuarios.md) y el ADR 0007 del catálogo
  (`../catalogo-service/docs/adr/0007-autorizacion-token-de-servicio.md`)

## Contexto

CU-03 y CU-04 piden la agenda vigente al catálogo (`GET /api/service/professionals/{id}/agenda`).
DPT-16 decidió que turnos no reenvía el JWT del usuario: usa un **token técnico de servicio**
propio. El catálogo fijó en su ADR 0007 cómo lo autoriza:

- `ROLE_SERVICE` y **ningún** rol de usuario;
- `aud` que contenga `catalogo-service` e `iss` igual a `turnos-service`;
- `iat` y `exp` presentes, `exp − iat` de hasta 5 minutos e `iat` a lo sumo 60 s en el futuro.

Si falta cualquiera, el catálogo responde `403`. Sin token, con la firma inválida o vencido,
responde `401`.

El token se firma con la **misma clave HS512** que los tokens de usuario (ADR 0003). Por eso la
firma prueba quién lo firmó, pero no qué clase de token es. Hasta este issue, turnos aceptaba en sus
endpoints cualquier token firmado con la clave, también los suyos de servicio, y uno sin `exp`.

## Decisión

### El emisor (`security.ServiceTokenIssuer`)

| Claim | Valor |
| --- | --- |
| `sub`, `iss` | `turnos-service` |
| `aud` | `["catalogo-service"]` (Nimbus lo escribe como texto si tiene un solo valor; el catálogo lo lee igual como lista) |
| `auth` | `ROLE_SERVICE` |
| `iat`, `exp` | siempre; `exp = iat + vida` |

- Lo firma el `JwtEncoder` que ya existe, sin dependencias nuevas.
- **Un token nuevo por pedido, sin caché.** Firmar HS512 con Nimbus cuesta unos 12 µs (medido en
  el #10: tres rondas de 50.000 firmas), contra cientos de milisegundos de red. Un caché sumaría un
  margen de renovación, sincronización entre hilos y la regla de `iat` futuro, sin ganancia medible.
- Vive en `security`, el paquete transversal, y no en el adaptador del catálogo. Usa el
  `JwtEncoder` y las constantes de los tokens, y queda junto a la regla que rechaza estos tokens.
  Las features pueden usar `security` (reglas de ArchUnit).
- `iss` y `aud` son constantes, igual que en el catálogo: son las identidades del contrato entre los
  dos servicios, no datos del despliegue.

### La vida configurable, con un máximo que impide arrancar

`security.service-token.lifetime` (`SERVICE_TOKEN_LIFETIME`), por defecto `5m`, validada al arrancar
con Bean Validation (`@Validated`, `@DurationMin(seconds = 30)` y `@DurationMax(minutes = 5)` de
Hibernate Validator):

- **Más de 5 minutos no arranca.** Si arrancara, el catálogo respondería `403` a cada pedido de
  agenda. Es mejor fallar al levantar que en cada reserva.
- **Menos de 30 s, 0 o negativa tampoco.** El token tiene que llegar vigente después de hasta 7 s de
  llamada (DPT-13) y con relojes que no coinciden exactamente.
- **Con exactamente 5 minutos cumple la regla del catálogo.** Nimbus trunca `iat` y `exp` a
  segundos, los dos hacia abajo, así que `exp − iat` queda en 300 s justos. Lo prueba un test que
  decodifica el token emitido y le aplica las reglas de `ServiceTokenAuthorizationManager`.

### Turnos rechaza los tokens de servicio en sus propios endpoints

Como el catálogo desde su #35 (`security.TokenAuthorizations`):

- Las reglas de `/api/**`, `/api/admin/**`, `/v3/api-docs/**` y `/management/**` piden el rol que
  corresponde **y que no esté `ROLE_SERVICE`**. Un token de servicio, o uno mixto con `ROLE_ADMIN`,
  da `403`. `/api/**` antes aceptaba cualquier token autenticado; ahora pide `ROLE_USER` o
  `ROLE_ADMIN`.
- Lo que marca un token como de servicio es `ROLE_SERVICE`, no el `aud`: un token de servicio mal
  armado puede no traerlo.
- `/management/info` y `/management/health` siguen públicas: responden lo mismo con cualquier token
  que sin token.

### `exp` obligatorio en todos los tokens

El decoder suma, a los validadores por defecto, un `JwtClaimValidator` que exige `exp`. El
validador por defecto deja pasar un token sin `exp`, que no vencería nunca (medido en el #35 del
catálogo). Un token sin `exp` responde `401`, como uno vencido, y lo cuenta el mismo contador. Los
tokens que emite turnos siempre traen `exp`.

## Alternativas consideradas

| Alternativa | Motivo para descartarla |
| --- | --- |
| Reenviar el JWT del usuario al catálogo | Descartado en DPT-16: el catálogo no podría distinguir una llamada de turnos de una de la app. |
| Cachear el token hasta poco antes de que venza | Ver arriba: 12 µs por firma no justifican la complejidad. |
| Validar la vida con código propio (por ejemplo, en el constructor de la propiedad) | Bean Validation ya se usa en las propiedades (`CatedraApiProperties`), y el mensaje de error nombra la propiedad. |
| Recortar la vida a 5 minutos en silencio si se configura más | Escondería un error de configuración. |
| El emisor en el adaptador del catálogo | Repartiría entre dos paquetes las dos caras del mismo contrato (emitir y rechazar). |
| Una clave distinta para los tokens de servicio | Haría falta otra variable en los dos servicios y otro decoder en el catálogo. El catálogo ya resolvió la distinción por claims (su ADR 0007). |

## Consecuencias

- **Positivas:**
  - Un token de servicio nunca sirve en los endpoints de usuario de turnos, y uno de usuario nunca
    sirve para la agenda del catálogo.
  - Una vida mal configurada se ve al arrancar, no en cada reserva.
  - Ningún token sin `exp` pasa.
- **Negativas:**
  - Con HS512, la vida corta acota un **token** filtrado, no una **clave** filtrada: con la clave se
    firma un token nuevo en cada pedido. Es la limitación documentada del ADR 0003.
  - Si el catálogo cambia sus reglas, el test de este lado no se entera solo: copia las reglas. Hay
    que actualizar los dos.
- **Seguridad:** el token de servicio nunca se loguea ni llega a la app. El aspecto de `dev` ya no
  imprime valores (#10).
