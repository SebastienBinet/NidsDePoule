# ADR-007: Project Repository Structure

**Status:** Accepted
**Date:** 2025-02-17
**Decision makers:** Sébastien Binet, Claude (AI assistant)

## Monorepo Structure

All components live in one repository for simplicity. The project is small
enough that a monorepo reduces overhead (no need for separate repos, CI
pipelines, or version coordination).

```
NidsDePoule/
├── README.md                    # Project overview and quick start
├── LICENSE
├── .gitignore
│
├── docs/
│   └── architecture/            # Architecture Decision Records (this dir)
│       ├── 001-project-overview.md
│       ├── 002-technology-choices.md
│       ├── 003-system-architecture.md
│       ├── 004-data-formats.md
│       ├── 005-development-strategy.md
│       ├── 006-open-questions.md
│       └── 007-project-structure.md
│
├── proto/                       # Protocol Buffer definitions (shared)
│   ├── nidsdepoule.proto        # Main protobuf schema
│   └── README.md                # How to regenerate code from .proto
│
├── android/                     # Android app (Kotlin)
│   ├── app/
│   │   ├── src/
│   │   │   ├── main/
│   │   │   │   ├── java/fr/nidsdepoule/app/
│   │   │   │   │   ├── MainActivity.kt
│   │   │   │   │   ├── sensor/           # Accelerometer & GPS
│   │   │   │   │   ├── detection/        # Hit detection & car mount
│   │   │   │   │   ├── reporting/        # Network upload & batching
│   │   │   │   │   ├── storage/          # Local SQLite buffer
│   │   │   │   │   └── ui/              # Compose UI screens
│   │   │   │   └── proto/               # Generated protobuf code
│   │   │   └── test/                    # Unit tests
│   │   │       └── java/fr/nidsdepoule/app/
│   │   │           └── detection/       # Hit detection tests
│   │   └── build.gradle.kts
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── gradle.properties
│
├── server/                      # Python server
│   ├── server/
│   │   ├── __init__.py
│   │   ├── main.py              # FastAPI app entry point
│   │   ├── api/
│   │   │   ├── __init__.py
│   │   │   └── hits.py          # Hit report endpoints
│   │   ├── queue/
│   │   │   ├── __init__.py
│   │   │   └── ingestion.py     # Async ingestion queue
│   │   ├── storage/
│   │   │   ├── __init__.py
│   │   │   └── writer.py        # Disk storage writer
│   │   └── proto/               # Generated protobuf code
│   │       └── nidsdepoule_pb2.py
│   ├── tests/
│   │   ├── __init__.py
│   │   ├── test_api.py
│   │   ├── test_queue.py
│   │   └── test_storage.py
│   ├── requirements.txt
│   └── pyproject.toml
│
├── tools/                       # Developer tools
│   ├── simulator/
│   │   ├── __init__.py
│   │   ├── simulate.py          # Hit report simulator
│   │   └── routes.py            # Simulated driving routes
│   └── scripts/
│       ├── generate_proto.sh    # Regenerate protobuf code
│       └── reset_data.sh        # Developer data reset tool
│
└── web/                         # Web dashboard (Phase 3, future)
    └── (to be defined)
```

## Rationale

- **Shared `proto/` directory:** The `.proto` file is the single source of
  truth for the data format. Both Android and server code generators read from
  here.
- **Flat top-level:** Each major component (android, server, tools, web) is a
  top-level directory. Easy to navigate.
- **Tests colocated:** Android tests follow standard Gradle convention
  (`src/test/`). Server tests are in `server/tests/`.
- **Tools separate:** The simulator and scripts are developer tools, not part
  of the production codebase.
- **Docs in `docs/`:** Architecture decisions are versioned alongside the code.
  This ensures they're always accessible and up to date.
