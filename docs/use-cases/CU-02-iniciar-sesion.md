# CU-02: Iniciar sesión

## Objetivo

Que el usuario final obtenga un **JWT de usuario** emitido por turnos, que la app usa durante la
sesión para llamar a turnos y al catálogo.

## Actores

- **Principal:** Usuario final, desde la app KMP.

## Disparador

El usuario ingresa su login y contraseña en la app.

## Precondiciones

- El usuario está registrado y activo (CU-01).

## Flujo principal

1. La app envía `username`, `password` y, opcionalmente, `rememberMe` al endpoint de
   autenticación de JHipster (`POST /api/authenticate` de turnos).
2. Turnos valida el formato del pedido.
3. Turnos busca el usuario y compara la contraseña contra el hash guardado.
4. Turnos verifica que el usuario esté activo.
5. Turnos emite un JWT de usuario firmado con la clave de firma del proyecto (externalizada por
   variable de entorno), con el login como sujeto, las autoridades del usuario y su vencimiento.
   El token **no** lleva `ROLE_SERVICE`, `aud=catalogo-service` ni `iss=turnos-service` (DPT-16),
   así el catálogo lo distingue del token técnico de servicio.
6. Turnos responde `200 OK` con el token (`id_token`).
7. La app guarda el token durante la sesión y lo envía como `Authorization: Bearer` a turnos y al
   catálogo (DP-02 del catálogo).

## Flujos alternativos

- **2a. Pedido mal formado** (falta `username` o `password`): `400` en `problem+json`.
- **3a. Usuario inexistente o contraseña incorrecta:** `401`, sin indicar cuál de los dos falló.
- **4a. Usuario desactivado** (por ejemplo, por un admin): `401`.

## Postcondiciones

- **De éxito:** la app tiene un JWT de usuario válido. Turnos no guarda estado de sesión: cada
  pedido se autoriza validando firma, vigencia y autoridades del token.
- **De fallo:** no se emite token. La contraseña no aparece en la respuesta ni en los logs.

## Decisiones del proyecto

- **DPT-16 (separación de tokens):** los JWT de usuario no llevan los claims del token técnico de
  servicio que turnos usa con el catálogo (`ROLE_SERVICE`, `aud=catalogo-service`,
  `iss=turnos-service`). El catálogo rechaza un token de usuario en su endpoint de agenda (CU-07
  del catálogo) y rechaza el token técnico en sus endpoints de usuario (DP-06 del catálogo).
- **DPT-21 (dueño por `id`):** el token identifica al usuario por su login, pero los procesos y
  reservas se asocian al `id` interno. Cómo se obtiene el id a partir del token se define en el
  issue de la base JHipster.

## Notas

- Este JWT es la identidad del **usuario final**. Es distinto del JWT técnico de la cátedra, que
  usa el backend con la API, Redis y Kafka de la cátedra y que nunca llega a la app (contrato §2).
- La forma de compartir la clave de firma con el catálogo se define en el issue de la base
  JHipster.

## Respaldo en las consignas

- Enunciado §3.2: el backend emite un JWT que la interfaz usa durante la sesión; identidades de
  usuario final y de cuenta técnica separadas.
- Enunciado §5 punto 1: autenticar a cada usuario final.
- Enunciado §9: emitir JWT al autenticar, exigirlo y validarlo, externalizar secretos.
- Contrato §2: el JWT técnico de cátedra nunca se envía a KMP.
