# Validación del blueprint y de su integridad

Este documento prueba únicamente la integridad de los **textos y contratos**. No prueba que el producto Studio exista, que sus UAT/AAT se hayan ejecutado, ni que haya releases.

```bash
python3 - <<'PY'
import hashlib, json
from pathlib import Path
root=Path.cwd()
m=json.loads((root/'MANIFEST.json').read_text())
for item in m['files']:
 p=root/item['path']
 assert p.is_file(), item['path']
 digest=hashlib.sha256(p.read_bytes()).hexdigest()
 assert digest==item['sha256'], (item['path'],digest)
print('Blueprint file digests verified:',len(m['files']))
PY
```

El agente debe verificar referencias GitHub y la versión actual de `main` al ejecutar S0; un digest de documento no es certificación del upstream. El archivo `contracts/openapi.yaml` es una propuesta OpenAPI 3.1 coherente a nivel de sintaxis, no un servidor ni una suite implementada.
