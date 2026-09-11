# Guía de etiquetado — Laboratorio de Biotecnología UTEQ

> **Nota de estado:** esta guía describe el pipeline local original (7 clases, YOLO11n, ver
> aviso al inicio de `ml/README.md`). El modelo final integrado en la app tiene **17 clases**
> (YOLO11m, ver `README.md` de la raíz). El procedimiento de etiquetado en sí sigue siendo
> válido para etiquetar fotos nuevas; solo actualiza `ml/classes.json` a las 17 clases reales
> antes de generar `labels.txt` con `scripts/sync_classes.py` si vas a retomar este flujo.

Esta guía define cómo etiquetar las fotografías para el detector YOLO. Su
objetivo es que **cualquier persona que etiquete en distintos momentos produzca
etiquetas consistentes entre sí**, incluso si se agregan fotos nuevas meses
después.

## 1. Herramienta

No se impone una herramienta específica. Cualquiera que exporte en formato
YOLO (`class_id x_center y_center width height`, normalizado 0–1, un archivo
`.txt` por imagen) sirve. Opciones razonables:

- **LabelImg** — más simple, 100% local, sin cuenta ni subida de fotos.
- **CVAT** (self-hosted o cvat.ai) — más cómodo para lotes grandes, soporta
  varios etiquetadores.
- **Label Studio** — flexible, self-hosted.
- **Roboflow** — cómodo pero sube las fotos a un servicio en la nube; usarlo
  solo si eso es aceptable para las fotos del laboratorio.

Recomendación para este proyecto (datasets pequeños, un solo etiquetador,
fotos que no deberían salir del equipo local): **LabelImg** en modo YOLO.

Verificado en este equipo (Windows, Python 3.12.10): `pip install labelImg`
instala correctamente (LabelImg 1.8.6 + PyQt5 5.15.11 como wheels
precompilados, sin errores de compilación) y crea el ejecutable
`labelImg.exe`. Ya está instalado en `ml/venv_labelimg/` (entorno dedicado,
separado de `ml/venv/` para no mezclar las dependencias pesadas de
`ultralytics`/`torch` con las de la interfaz gráfica de LabelImg).

## 2. Correspondencia imagen ↔ label

Por cada imagen `equipo_001.jpg` debe existir un `equipo_001.txt` **en la
misma carpeta**, con una línea por objeto etiquetado:

```
class_id x_center y_center width height
```

- Todos los valores excepto `class_id` normalizados entre 0 y 1
  (`x_center = x_center_px / ancho_imagen`, igual para el resto).
- `class_id` debe ser exactamente el de `ml/classes.json` (ver tabla abajo).
- Una imagen sin ningún equipo válido visible puede tener un `.txt` vacío o
  no tener `.txt` (en ese caso `scripts/prepare_dataset.py` la ignora y avisa).
- Guardar el `.txt` junto a la foto (misma carpeta que `ml/raw/<clase>/` o la
  carpeta que estés usando para etiquetar) es lo que espera
  `scripts/prepare_dataset.py`.

## 3. Clases y class_id (fijos — ver `ml/classes.json`)

| class_id | nombre interno | nombre visible | área |
|---|---|---|---|
| 0 | `bod_sensor` | BOD Sensor | Cultivo de tejidos vegetales |
| 1 | `lux_fc_meter` | Broad Range LUX/FC Meter | Cultivo de tejidos vegetales |
| 2 | `medidor_multiparametro` | Medidor multiparámetro electroquímico de mesa | Cultivo de tejidos vegetales |
| 3 | `electroforesis_owl_easycast` | Sistema de electroforesis horizontal Thermo Scientific Owl EasyCast B1-BP | Cultivo de tejidos vegetales |
| 4 | `armario_calefactor_ule600` | Armario calefactor ULE 600 | Microbiología |
| 5 | `horno_secado` | Horno de secado | Microbiología |
| 6 | `uv_pcr_workstation` | UV PCR Workstation | Microbiología |

**Nunca inventes un `class_id` nuevo a mano.** Si aparece un equipo nuevo,
primero se agrega a `ml/classes.json` con el siguiente ID disponible (ver
`ml/README.md`, sección "Agregar una clase nueva"), y solo después se etiqueta.

## 4. Reglas de la caja delimitadora (bounding box)

1. **Marcar el equipo completo visible**, no solo una parte característica.
2. La caja debe quedar **lo más ajustada posible** al contorno real del
   equipo — sin holgura grande de fondo alrededor.
3. **No incluir grandes zonas de fondo** dentro de la caja (mesa, pared,
   otros objetos no relacionados).
4. Si el equipo está **parcialmente oculto pero se puede identificar con
   confianza** (por ejemplo, tapado en parte por una mano o por otro objeto),
   etiquetarlo igual, ajustando la caja a la parte visible.
5. Si **varios equipos válidos** (de las 7 clases) aparecen en la misma
   foto, etiquetar **todos**, cada uno con su propia caja y su `class_id`
   correcto.
6. **No etiquetar** objetos que no correspondan a ninguna de las clases
   definidas (mesas, estanterías, otros instrumentos no incluidos en
   `classes.json`, personas, etc.).
7. La clase se mantiene igual sin importar el ángulo, la distancia o la
   iluminación de la foto — lo que identifica la clase es el equipo, no la
   toma.
8. Si tienes duda razonable sobre si algo es o no el equipo correcto, es
   preferible **no etiquetarlo** a etiquetarlo mal (una etiqueta incorrecta
   entrena al modelo a equivocarse; una imagen sin etiquetar simplemente no
   aporta esa muestra).

## 5. Fotos en ráfaga / casi idénticas

No hace falta que te preocupes por esto al etiquetar — `scripts/prepare_dataset.py`
agrupa automáticamente fotos casi idénticas (ráfagas) para que queden
completas dentro de un mismo split (train, val o test) y no se filtren datos
entre conjuntos. Simplemente etiqueta cada foto de forma independiente y
correcta.

## 6. Antes de entrenar

Después de etiquetar (o de agregar fotos nuevas), siempre correr, en este
orden:

```bash
python scripts/validate_raw_dataset.py --raw-dir "<ruta a tus fotos>"
python scripts/prepare_dataset.py --labeled-dir "<ruta a tus fotos etiquetadas>"
python scripts/validate_labels.py
```

Si `validate_labels.py` reporta errores graves, **no se debe entrenar** hasta
corregirlos.

## 7. LabelImg paso a paso (Windows)

### A. Instalar

Ya está instalado en `ml/venv_labelimg/` (verificado en este equipo,
incluyendo que la ventana abre correctamente). Para reinstalarlo o
instalarlo en otro equipo:

```bash
cd ml
python -m venv venv_labelimg
venv_labelimg\Scripts\activate
pip install setuptools labelImg
python scripts\patch_labelimg.py
```

**`scripts/patch_labelimg.py` es un paso obligatorio, no opcional.** LabelImg
1.8.6 tiene un bug real y muy conocido (varios issues abiertos en su propio
repositorio, tanto en tzutalin/labelImg como en el fork HumanSignal/labelImg)
con PyQt5 5.15.x: al dibujar (mover el mouse sobre el lienzo o crear una
caja) se cierra con
`TypeError: ... drawLine(...) argument 1 has unexpected type 'float'`,
porque `canvas.py` pasa coordenadas `float` a funciones de Qt que ya solo
aceptan `int`. `scripts/patch_labelimg.py` aplica exactamente la corrección
documentada por la comunidad (envolver esas coordenadas en `int(...)`) sobre
el archivo instalado en el venv. Es idempotente: puedes correrlo varias
veces sin problema, y hay que volver a correrlo cada vez que se reinstale
`labelImg` desde cero (una reinstalación trae de vuelta el archivo sin
parchear). Ya está aplicado en `ml/venv_labelimg/` actual — verificado que
LabelImg abre sin errores.

**`setuptools` es obligatorio junto a `labelImg`, no opcional.** En Python
3.12 (el usado en este equipo) el módulo `distutils` fue eliminado de la
librería estándar, pero `labelImg.py` todavía hace `import distutils.spawn`
al arrancar; sin `setuptools` instalado (que provee un `distutils` de
compatibilidad) falla con `ModuleNotFoundError: No module named 'distutils'`.
Esto lo verificamos y corregimos en este equipo: sin `setuptools` LabelImg no
arranca en absoluto; con `setuptools` arranca correctamente.

Si en algún momento hay que recrear el entorno desde cero, créalo siempre
con su nombre final (`venv_labelimg`) — **no lo crees con otro nombre y
luego renombres la carpeta**: los `.exe` de `Scripts/` (incluido
`labelImg.exe`) llevan grabada la ruta absoluta a `python.exe` en el momento
de la instalación, y renombrar la carpeta después rompe esa referencia
("Fatal error in launcher").

### B. Abrir una carpeta de fotografías correctamente

Lánzalo pasando **la carpeta de imágenes** y **el archivo de clases** como
argumentos (así no hay que tocar nada dentro de la interfaz):

```bash
ml\venv_labelimg\Scripts\labelImg.exe "<ruta de la carpeta de la clase>" "C:\Users\stefy\AndroidStudioProjects\DeteccindeequiposLaboratoriodeBiotecnologa\ml\labels.txt"
```

Por ejemplo, para `armario_calefactor_ule600` (ruta real según
`ml/raw_source_map.json`):

```bash
ml\venv_labelimg\Scripts\labelImg.exe "C:\Users\stefy\Documents\RECOPILACION DE INFORMACION - LABORATORIO DE BIOTECNOLOGIA\AREA - MICROBIOLOGIA\Armario calefactor ULE 600\Armario calefactor ULE 600" "C:\Users\stefy\AndroidStudioProjects\DeteccindeequiposLaboratoriodeBiotecnologa\ml\labels.txt"
```

No se pasa un tercer argumento (carpeta de guardado): así LabelImg guarda cada
`.txt` **en la misma carpeta de la imagen**, que es la convención que usa
`ml/LABELING_GUIDE.md` y `scripts/prepare_dataset.py`.

### C. Seleccionar formato YOLO

Por defecto LabelImg guarda en formato PascalVOC (XML), no YOLO. En la barra
de herramientas / menú izquierdo hay un botón que dice **"PascalVOC"** — haz
clic en él (cicla PascalVOC → YOLO → CreateML → PascalVOC...) hasta que diga
**"YOLO"**. LabelImg recuerda esta preferencia entre sesiones, pero conviene
comprobarlo cada vez que abras una carpeta nueva.

### D. Cargar las clases en el orden correcto

Si abriste LabelImg como en el paso B (pasando `ml/labels.txt` como segundo
argumento), las 7 clases ya están cargadas, en este orden exacto (el mismo
que `ml/classes.json`, generado por `scripts/sync_classes.py` — no se
mantiene una segunda lista manual):

```
bod_sensor
lux_fc_meter
medidor_multiparametro
electroforesis_owl_easycast
armario_calefactor_ule600
horno_secado
uv_pcr_workstation
```

Verifícalo en el panel derecho ("Label List" / lista de clases).

### E. Dibujar una bounding box

1. Clic en **"Create RectBox"** (o tecla `W`).
2. Arrastra desde una esquina del equipo hasta la esquina opuesta, ajustando
   al contorno real (ver reglas en la sección 4 de esta guía).
3. Al soltar, aparece un desplegable para elegir la clase — selecciona la
   correcta (debe coincidir con la carpeta que estás etiquetando, salvo que
   la imagen tenga más de un equipo válido visible).
4. Repite si hay más de un equipo de las 7 clases en la misma foto.

### F. Corregir una bounding box mal dibujada

Con la herramienta de selección (`Ctrl+E` o clic normal, no "Create RectBox"):
arrastra cualquiera de las esquinas/lados de la caja para redimensionarla, o
arrastra el interior para moverla entera. No hace falta borrarla y rehacerla.

### G. Cambiar la clase de una caja ya dibujada

Doble clic sobre la caja (o selecciónala y usa el botón "Edit Label" /
`Ctrl+E`) y elige la clase correcta en el desplegable. Esto no afecta a
ninguna otra imagen ni cambia el significado del `class_id`: solo corrige
qué `class_id` tiene *esa* caja en *esa* imagen.

### H. Eliminar una bounding box incorrecta

Selecciónala (clic sobre ella) y pulsa `Delete` (o clic derecho → "Delete
RectBox").

### I. Guardar

`Ctrl+S` guarda el `.txt` de la imagen actual. Actívalo también en el menú
View → "Auto Save mode" si prefieres que guarde automáticamente al pasar de
imagen (recomendado para no perder etiquetas por accidente).

### J. Verificar dónde se guardó

El `.txt` debe aparecer **en la misma carpeta que la imagen**, con el mismo
nombre (`IMG_0001.jpg` → `IMG_0001.txt`). LabelImg también crea ahí un
`classes.txt` auxiliar (lista de clases usada por esa carpeta) — no lo
edites a mano; siempre proviene de `ml/labels.txt`.

### K. Continuar con otra carpeta sin alterar los class_id

Cierra y vuelve a abrir LabelImg apuntando a la siguiente carpeta, siempre
pasando el mismo `ml/labels.txt` como archivo de clases (paso B). Como el
`class_id` de cada nombre depende únicamente del orden en `ml/labels.txt`
(que sale de `ml/classes.json`, nunca reordenado), el significado de cada
`class_id` es el mismo en todas las carpetas y sesiones.

## 8. Corregir etiquetas más adelante

Puedes en cualquier momento reabrir una imagen ya etiquetada (LabelImg carga
automáticamente su `.txt` si existe) y:

- mover una caja (arrastrándola),
- redimensionarla (arrastrando sus bordes),
- eliminarla (`Delete`),
- agregar una caja nueva,
- corregir el `class_id` de una caja (doble clic → cambiar clase),

y guardar de nuevo (`Ctrl+S`) — esto solo afecta al `.txt` de esa imagen.
**Esto es distinto de cambiar el significado global de un `class_id`**: por
ejemplo, corregir la clase de una caja mal etiquetada en una foto no cambia
que `class_id 1` siga significando `lux_fc_meter` en todo el proyecto — esa
correspondencia solo cambia editando `ml/classes.json` (y nunca reordenando
IDs existentes, ver `ml/README.md`).

Después de corregir etiquetas, vuelve a correr
`python scripts/validate_labels.py --images-dir <carpeta> --labels-dir <carpeta>`
(o `prepare_dataset.py` de nuevo si ya habías generado el dataset) antes de
entrenar.

## 9. Pre-etiquetado asistido con YOLO11n (opcional, acelera etiquetar fotos nuevas)

Una vez que ya existe un `best.pt` entrenado (aunque sea preliminar), se
puede usar para generar automaticamente un punto de partida de etiquetas
sobre fotos **nuevas** de los mismos 7 equipos, en vez de dibujar cada caja
desde cero. **Estas etiquetas generadas por el modelo son siempre
preliminares — nunca se aceptan como definitivas sin revision humana.**

Flujo completo:

```
foto nueva -> pre-etiquetar con YOLO11n (prelabel_new_images.py)
           -> revisar en LabelImg (corregir caja/clase, borrar falsos
              positivos, agregar cajas que el modelo no detecto)
           -> validate_labels.py (debe pasar sin errores)
           -> recien ahi, incorporar al dataset con prepare_dataset.py
```

### 9.1 Generar las pre-etiquetas

```bash
python scripts/prelabel_new_images.py \
  --images-dir "<carpeta con las fotos nuevas>" \
  --output-labels-dir "<carpeta donde escribir los .txt>" \
  --weights ml/models/preliminary/best.pt \
  --conf 0.25
```

- Usa `--output-labels-dir` = la carpeta hermana `" - txt"` (misma
  convencion que el resto de esta guia) si vas a revisar despues en
  LabelImg con la carpeta de imagenes y la de labels separadas.
- Por defecto **nunca sobrescribe** un `.txt` que ya exista en
  `--output-labels-dir` (lo salta y lo avisa) — asume que ya fue revisado o
  etiquetado a mano. Solo con `--overwrite` explicito se regenera.
- No modifica ninguna imagen original, no toca `ml/dataset/`, no llama a
  `prepare_dataset.py` ni a `train.py`.
- Imprime un resumen: imagenes procesadas, con/sin deteccion, total de
  bounding boxes, detecciones y confianza media por clase.
- El script mismo imprime, al final, el comando exacto de LabelImg para
  revisar lo que acaba de generar (pasando `--output-labels-dir` como
  tercer argumento, para que LabelImg cargue/guarde ahi en vez de en la
  carpeta de imagenes).

### 9.2 Revisar las pre-etiquetas en LabelImg (obligatorio, nunca omitir)

Abre LabelImg apuntando a la carpeta de imagenes, `ml/labels.txt`, y la
carpeta de labels generada (tercer argumento) — LabelImg carga
automaticamente cada `.txt` existente como las cajas iniciales de esa
imagen:

```bash
ml\venv_labelimg\Scripts\labelImg.exe "<carpeta de imagenes>" "ml\labels.txt" "<carpeta de labels generada>"
```

Para cada imagen:

- Si la caja/clase es correcta: no hacer nada.
- Si la caja esta mal ajustada: corregirla (sección 4/F de esta guía).
- Si la clase es incorrecta: cambiarla (sección G).
- Si es un falso positivo (el modelo detecto algo que no es el equipo, o
  detecto un equipo de otra clase): eliminar esa caja (sección H).
- Si el modelo no detecto un equipo que si esta visible en la foto:
  agregar la caja manualmente (sección E).

Guardar (`Ctrl+S`) despues de revisar cada imagen.

### 9.3 Validar antes de incorporar al dataset

Solo despues de revisar TODAS las imagenes de esa tanda:

```bash
python scripts/validate_labels.py --images-dir "<carpeta de imagenes>" --labels-dir "<carpeta de labels revisada>"
```

Si pasa sin errores, recien ahi esas fotos+labels quedan listas para
incorporarse en la siguiente corrida de `prepare_dataset.py` (que reconstruye
el split 70/15/15 completo con todas las fotos disponibles a ese momento,
no solo las nuevas).

## 10. Prueba inicial antes de etiquetar todo (obligatoria)

Antes de etiquetar las ~180 fotos, se recomienda una prueba pequeña con
**`uv_pcr_workstation`** (`class_id 6`): es la clase más pequeña (6 fotos
reales), así que etiquetarla completa sirve como prueba real del flujo *y*
ya es trabajo útil (no se desperdicia).

Pasos de la prueba:

1. Etiquetar las 6 fotos de `uv_pcr_workstation` siguiendo los pasos A–K.
2. Verificar que se crearon 6 archivos `.txt` en esa misma carpeta.
3. Correr, apuntando a esa carpeta:
   ```bash
   python scripts/validate_labels.py --images-dir "<ruta de la carpeta de uv_pcr_workstation>" --labels-dir "<misma ruta>"
   ```
4. Revisar la salida: debe reportar 6 imágenes, `class_id` = 6 en todas las
   instancias, sin errores de coordenadas ni formato, y (gracias a la
   corrección de rutas largas en `common.py`) sin fallos aunque la ruta sea
   larga.

**Detenerse ahí.** No se arma el split 70/15/15 completo (`prepare_dataset.py`)
hasta confirmar que esta prueba salió bien.
