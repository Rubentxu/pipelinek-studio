# Trazabilidad — necesidades → arquitectura → hitos → pruebas

| Necesidad | Especificación | ADR | Hito | UAT | AAT |
|---|---|---|---|---|---|
| P-01 Scratchpad real | UX_AND_OBSERVABILITY, RUNNER_COMPILER_AND_SECURITY | 001/002 | S0/S1 | 001/003 | ARCH-004, SEC-001 |
| P-02 Errores sin falsificación | HTTP_AND_STREAMING_API, ANALYSIS_DETEKT_LSP | 006/010 | S0/S3 | 004/005/027/032 | CPP-001..005, AN-005 |
| P-03 Historial reproducible | DATA_MODEL_AND_PERSISTENCE | 003/005 | S0/S1 | 002/011/012/016 | DUR-001..006 |
| P-04 MCP/CLI paridad | MCP_AND_AGENT_PROTOCOL, HTTP_AND_STREAMING_API | 004 | S0/S4 | 013/036/041 | API-001..008, MCP-001..008 |
| P-05 Catálogo de Steps real | STEP_PLUGIN_LAB | 008/009 | S2 | 020/026 | PLG-001..007 |
| P-06 Plugin testkit | STEP_PLUGIN_LAB | 002/008 | S2 | 021..025 | PLG-002..006, ABI-001..003 |
| P-07 Compile-only/no effects | RUNNER_COMPILER_AND_SECURITY, ANALYSIS_DETEKT_LSP | 006/007 | S3 | 009/032 | CPP-001/002, SEC-003 |
| P-08 Detekt | ANALYSIS_DETEKT_LSP | 010 | S3 | 030/031/033 | AN-001..008 |
| P-09 Run compare/agent | MCP_AND_AGENT_PROTOCOL, UX_INTERACTIVE_CONSOLE | 004/005 | S4 | 036..046 | MCP-005..008, DUR-002/003 |
| P-10 VS Code seam | INTEROPERABILITY_AND_VERSIONING | 012 | S5 | 039/040 | ABI-005/006, API-007/008 |
| Global seguridad | RUNNER_COMPILER_AND_SECURITY | 007 | Todos | 009/015/019/021/043 | SEC-001..010 |
| Global release remoto | RELEASE_AND_INTEGRATION_PROTOCOL | 011 | Todos | Todos aplicables | REL-001..010 |

Nota: los IDs abreviados se expanden como `UAT-xxx` y `AAT-<FAMILIA>-xxx`, especificados en `../05-testing/`. Este documento permite auditar si una capacidad viene acompañada de pruebas reales; la trazabilidad **no** demuestra por sí misma ejecución.
