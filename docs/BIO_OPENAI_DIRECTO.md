# Bio sin computadora ni servidor propio

El APK envía preguntas directamente a `https://api.openai.com/v1/responses` por HTTPS,
con `Authorization: Bearer ...`. Usa Volley, ya incluido; no necesita otro SDK.
El chat escrito y el de voz comparten `ChatRepository`.

## Compilar en Android Studio

1. Mantén el archivo local `backend/.env` existente con `OPENAI_API_KEY` y, opcionalmente,
   `OPENAI_MODEL` (por defecto `gpt-4o-mini`). No hace falta arrancar Python.
2. Sincroniza Gradle y genera el APK normalmente, o usa **Generate Signed App Bundle / APK**
   para firmar la versión release.
3. Instala el APK en el teléfono. Solo necesita conexión a Internet para las preguntas.
   Puedes apagar la computadora, desconectar USB y usar otra red o datos móviles.

La compilación incorpora la clave al APK por decisión expresa del propietario. No pide claves
al usuario final. El `.env` permanece fuera de Git y la clave no se imprime en logs. Las variables
de entorno `OPENAI_API_KEY` y `OPENAI_MODEL`, si existen, tienen prioridad sobre `.env`.
El usuario puede guardar una clave personal opcional desde Configuración y eliminarla para volver
a la incluida en el APK. Cambiar la clave predeterminada o el modelo requiere recompilar.

## Manuales y respuestas

- `BioManuales.java` conserva los 17 Vector Stores existentes, uno por equipo, y las mismas
  instrucciones documentales del backend. Si cambian los stores, mantenerlo sincronizado con
  `backend/app/services/equipo_manual_map.py`.
- Cada consulta incluye solo el store del equipo seleccionado. Sin equipo conocido se conserva
  el mensaje de falta de información; no se consultan manuales de otro equipo.
- `BioResponses.java` extrae texto y citas de `output[].content[]` del JSON REST.
- No hay reintentos automáticos de preguntas ni caché de respuestas. El tiempo máximo es 90 s.
- Ajustes comprueba el acceso al modelo en OpenAI; la consulta real verifica además el acceso
  al manual. Se mantienen mensajes controlados para problemas de red, autenticación y cuota.
- La detección de equipos continúa siendo local. La voz utiliza los servicios de reconocimiento
  y síntesis de Android existentes; su disponibilidad depende del teléfono.

La clave debe continuar activa y tener acceso a los Vector Stores y saldo/cuota en OpenAI.
No se necesita hosting propio, pero las consultas siguen utilizando la API de OpenAI.

## Validación

Pruebas del contrato REST: `gradlew.bat :app:testDebugUnitTest`.
Compilación: `gradlew.bat :app:assembleDebug :app:assembleRelease`.
Prueba opcional con una consulta real y sin imprimir credenciales:
`python backend/tests/check_android_direct_openai.py`.

Referencia oficial: [Responses API y file search](https://developers.openai.com/api/docs/guides/tools-file-search).

## Configuración opcional, manual PDF y voz

- Configuración permite guardar una clave personal cifrada en el teléfono. Esa clave se usa en
  chat, voz, comprobación de conexión y descarga de manuales. Al eliminarla se restaura la del APK.
- En el menú de tres puntos del chat o de voz, **Ver PDF del equipo** abre el manual original
  del equipo en contexto. El PDF **no se descarga**: está empaquetado localmente en
  `app/src/main/assets/manuales/<claseDetector>.pdf` (`ManualRepository`) y se abre dentro de
  Bio con navegación por páginas, sin usar Internet ni la OpenAI Files API (se probó descargar
  vía `GET /v1/files/{id}/content` y OpenAI la rechaza para archivos subidos con propósito
  `assistants`; por eso se empaquetan localmente en su lugar). El RAG (respuestas de texto)
  sigue consultando el Vector Store del equipo normalmente; esto solo afecta al botón "Ver PDF".
- Al terminar el saludo inicial, Bio inicia el reconocimiento y muestra **Habla ahora** cuando
  Android confirma que escucha. Se solicita permiso de micrófono si aún no está concedido.
  No se activa en segundo plano ni al interrumpir el saludo.
- La cámara usa `fitCenter` y el overlay la misma escala: conserva el campo visible completo
  sin ampliarlo para llenar la pantalla. `match_parent` pertenece a la capa de dibujo y no
  determina el tamaño de las cajas. Las etiquetas separan nombre y confianza.
