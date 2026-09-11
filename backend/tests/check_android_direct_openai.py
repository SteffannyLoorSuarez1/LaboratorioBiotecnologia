"""Prueba manual de la conexión que usa Android; no imprime la clave ni la respuesta.

Ejecutar desde la raíz con Python. Hace una consulta real breve a OpenAI.
"""
import ast
import json
from pathlib import Path
import urllib.error
import urllib.request


def main():
    root = Path(__file__).resolve().parents[1]
    values = {}
    for line in (root / ".env").read_text(encoding="utf-8-sig").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip().strip('"').strip("'")
    api_key = values.get("OPENAI_API_KEY", "")
    if not api_key:
        raise SystemExit("Falta la clave local; no se realizó ninguna consulta.")
    model = values.get("OPENAI_MODEL", "gpt-4o-mini")
    scope = {}
    for filename, names in [
        ("equipo_manual_map.py", {"EQUIPO_VECTOR_STORE_MAP"}),
        ("rag_service.py", {"MENSAJE_SIN_INFORMACION", "INSTRUCCIONES_SISTEMA"}),
    ]:
        tree = ast.parse((root / "app/services" / filename).read_text(encoding="utf-8"))
        for node in tree.body:
            if isinstance(node, ast.Assign) and any(
                isinstance(t, ast.Name) and t.id in names for t in node.targets
            ):
                exec(compile(ast.Module(body=[node], type_ignores=[]), filename, "exec"), {}, scope)
    payload = {
        "model": model,
        "instructions": scope["INSTRUCCIONES_SISTEMA"],
        "input": "Área: laboratorio. Equipo: horno de secado BIOBASE.\nPregunta: ¿Para qué sirve? Responde en una frase.",
        "tools": [{"type": "file_search", "vector_store_ids": [
            scope["EQUIPO_VECTOR_STORE_MAP"]["horno_secado_biobase"]]}],
        "store": False,
    }
    request = urllib.request.Request(
        "https://api.openai.com/v1/responses",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Authorization": "Bearer " + api_key, "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            result = json.load(response)
    except urllib.error.HTTPError as error:
        raise SystemExit(f"OpenAI devolvió HTTP {error.code}; cuerpo omitido.") from None
    except urllib.error.URLError:
        raise SystemExit("No se pudo conectar a OpenAI; detalle omitido.") from None
    blocks = [b for i in result.get("output", []) if i.get("type") == "message"
              for b in i.get("content", []) if b.get("type") == "output_text"]
    citations = [a for b in blocks for a in b.get("annotations", []) if a.get("type") == "file_citation"]
    assert result.get("status") == "completed", "Respuesta incompleta"
    assert any(b.get("text", "").strip() for b in blocks), "Falta texto REST"
    print("Responses API: completada. Texto recibido. Citas de manual:", len(citations))


if __name__ == "__main__":
    main()
