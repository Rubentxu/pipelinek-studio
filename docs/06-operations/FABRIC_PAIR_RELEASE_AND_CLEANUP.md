# Releases por hito y economía de harness — extensión operacional

**Respetar** `RELEASE_AND_INTEGRATION_PROTOCOL.md`, `RELEASE_RUNBOOK.md`, Studio `ROADMAP.md` (full suite integración y RC; autorización explícita de `gh release create`) y Fabric `docs/fabric-coordinated-evolution/coordination/PAIR_RELEASE_FLOW.md` / `MILESTONE_MATRIX.md` (pareja PK↔Fabric cuando API/artefactos cambien). Esta guía no cambia gates ya normativos; indica qué evidencia y tests de ciclo producen valor en F0–F4.

## Dos parejas compatibles, no dos roadmaps

1. **PK↔Fabric**: identificar distribución PK sellada y versión API; Fabric certifica consumo por tests reales. Si hay cambio PK, publicar PK primero y luego Fabric, almacenar/validar handoffs PK/Fabric existentes. Nunca reciclar tag.
2. **Fabric↔Studio**: instalar Fabric publicado y cliente Fabric desde Maven remoto antes de integrar Studio; si el cliente cambia, reconstruir Studio contra artefactos publicados y validar compatibilidad con servidor concreto. Se puede reutilizar la misma release PK certificada sin inventar otra versión.

## Secuencia de cada F

`BASELINE code/release remote → issue causal/ADR → cambios productivos + tests focales → UAT/AAT efímeros reales → contrato/CI merge → full integration gate → merge SHA remoto → build/release artefactos de ese SHA → publicación en GitHub y registro Maven/OCI según producto → instalación remota independiente → receipt pequeño + limpieza.`

Si política local compila/certifica RC antes de merge, preservar estricta identidad entre commit candidato e integrado; si un merge cambia los bytes se repite gate. **Sin autorización no publicar**, estado `BLOCKED_OPERATOR`, no mentir sobre release.

## Inventario real de producto

| Componente | Artefacto y canal esperado | Verificación |
|---|---|---|
| Studio CLI/app (JVM, frontend embebido si forma parte del paquete) | ZIP/TAR/JAR instalable en GitHub Releases según empaquetado real | ejecutar fuera del source tree, verificar `--version`/health/runs |
| Studio extensión VS Code | VSIX o canal nativo si efectivamente distribuido | instalar extensión empaquetada, comparar API |
| Fabric Server | distribución runnable publicada | iniciar server con bundle PK sellado, health/StartRun/ObserveRun |
| FabricClient + contracts + codecs/protobuf | **artefactos Maven JVM por módulo público** (Maven Central/Nexus/GitHub Packages según permisos) | resolver POM+transitivos sin `mavenLocal`, abrir conexión TLS real |
| PipelineK runtime/SDK | distribución/SDK oficial versión fijada en Fabric | manifest SHA, compat ABI/CLI; no depender del source checkout |

GitHub genera `Source code (zip/tar.gz)` automáticamente: no es el producto. No adjuntar `sources.zip` manual desde workspace; `.gitattributes export-ignore` solo si se autoriza y no excluye fuentes obligatorias para distribución Maven. Conservar `-sources.jar` exigido por registros y licencia/NOTICE/POM obligatorios.

## Matriz de gates mínima

| Momento | Pruebas y alcance | No hacer |
|---|---|---|
| Feature | build incremental + unitarios afectados + ley crítica de seam + UAT exploratorio solo si demuestra conducta pública | full suite en cada cambio |
| PR/merge | lint/compilación, contratos cambiados, integración más próxima, tests adversariales críticos | probar todas las plataformas sin impacto |
| Integración `main` | suite amplia existente de los módulos afectos y consumers reales, E2E frontera, sin false skip/cache | convertir errores en advisory |
| RC/release | artefacto firmado/identificado, instalación desde remoto, asset set completo + hash, compat servidor/cliente/PK + UAT y AAT mínimos | testear solo clases de src, aceptar ZIP incompleto |

## Plan de limpieza legacy

**Candidatos a analizar** (no borrar sin inventario de consumidores): `scripts/e2e-host.sh`/`e2e-podman.sh` y similares anclados a `rc.11`, `scripts/run-all-uats.sh` que reejecuta subUAT, `studio-cert` monolítico, `studio-infra-sdk/PipelineKRunner.kt`, `PipelineKRunReadPort.kt`, `PipelineKOutputReadPort.kt`, `PipelineKRuntimeProbe.kt`, `PipelineKStepCatalogReadPort.kt`. Sobre scripts falsos verdes (`if expected failure then ok`, `|| true`) rehacer o retirar cuando el smoke de Fabric cubra la garantía.

Medir p50/p95 por gate, dobles E2E, fallos falsos, test flakiness y número de scripts que solo comprueban archivos; ordenar por ratio riesgo/coste. Para cada borrado: garantía vigente + nuevo test + fallaría con mutación causal + autorización. Tests de concurrencia/security/durabilidad permanecen aunque las UAT temporales desaparezcan.

## Receipt mínimo de cada release

```
F-hito; SHA origin/main Studio/Fabric/PK; producto:versión; artifact SHA256;
coordenadas Maven; versiones SDK/ABI; tests de consumidor (PASS/FAIL/BLOCKED);
completitud de assets; pares compatibles; regresiones promovidas;
UAT/E2E/AAT temporales eliminadas; riesgos no resueltos; rollback último par verde.
```

No editar `docs/history` como parte de esta propuesta: crear receipt solo cuando existe release medido.
