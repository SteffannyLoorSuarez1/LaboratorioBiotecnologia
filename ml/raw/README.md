# ml/raw/

Carpeta reservada para las fotografías originales del laboratorio (o para
enlazar/copiar desde la ruta real que se indique), organizadas **una
subcarpeta por clase**, usando el nombre `raw_folder` de cada clase en
`../classes.json`:

```
ml/raw/
├── bod_sensor/
├── lux_fc_meter/
├── medidor_multiparametro/
├── electroforesis_owl_easycast/
├── armario_calefactor_ule600/
├── horno_secado/
└── uv_pcr_workstation/
```

**Intencionalmente vacía.** No hace falta copiar nada aquí:
`scripts/validate_raw_dataset.py --raw-dir <ruta>` apunta directamente a la
carpeta original del usuario. Como esa carpeta usa nombres de carpeta
"humanos" (con acentos, mayúsculas, subcarpetas duplicadas, nombres de
exportación de Google Drive, etc.) en vez de los `raw_folder` de
`../classes.json`, la ubicación real de cada clase está mapeada en
`../raw_source_map.json` (sin renombrar ni mover nada). Si `validate_raw_dataset.py`
reporta una clase "sin carpeta encontrada" o una carpeta "sin mapear", revisa
y actualiza ese archivo.

Reglas:

- Nunca se modifican ni eliminan fotos originales desde ningún script de
  `ml/`.
- Los archivos `.txt` de etiquetas (formato YOLO) se guardan **junto a cada
  foto**, con el mismo nombre (`equipo_001.jpg` ↔ `equipo_001.txt`) — ver
  `../LABELING_GUIDE.md`.
- `scripts/prepare_dataset.py` solo **copia** (nunca mueve) pares
  imagen+label ya etiquetados hacia `ml/dataset/`, que sí es un directorio
  generado y desechable.
