# Psar

Online store for Cambodia: storefront, inventory, promotions, points, subscriptions and an AI shopping assistant.
Spring Boot modular monolith + React + PostgreSQL + RabbitMQ.

> **Status:** Phase 0 — project skeleton. See `PRD.md` for the plan.

## Docs

| File | What it is |
|---|---|
| `PRD.md` | Product requirements, roles, features, milestones |
| `architecture.md` | Full technical design, data model, flows, queues, jobs |
| `essential-architecture.md` | One-page summary and the 12 rules |
| `CLAUDE.md` | Instructions for AI coding assistants |

## Tech stack

Java 21 · Spring Boot 3 · PostgreSQL + pgvector · Flyway · Spring Security + JWT · MapStruct · SpringDoc · RabbitMQ · Spring AI + Gemini · React + TypeScript + Vite · Tailwind CSS · TanStack Query · Docker · GitHub Actions

## Project structure

```
psar/
├── backend/                     Spring Boot (package-by-feature modular monolith)
│   └── src/main/java/com/psar/
│       ├── common/              config, security, exception, entity, web, util
│       ├── messaging/           domain events, outbox, RabbitMQ config
│       ├── user/ delivery/ catalog/ inventory/ order/ payment/
│       ├── promotion/ loyalty/ subscription/ notification/ audit/ ai/
│       │   └── controller/ service/ repository/ entity/ dto/{request,response}/ mapper/
│       └── PsarApplication.java
├── frontend/                    React + Vite
│   └── src/ app/ api/ components/ features/ hooks/ i18n/ lib/ types/
├── docker-compose.yml           postgres, rabbitmq, minio, backend, frontend
└── .github/workflows/ci.yml
```

## Run locally

Requirements: Java 21, Maven 3.9+, Node 22, Docker.

```bash
cp .env.example .env                              # fill in JWT_SECRET
docker compose up -d postgres rabbitmq minio      # infrastructure

cd backend && mvn spring-boot:run                 # http://localhost:8080
cd frontend && npm install && npm run dev         # http://localhost:5173
```

Or run everything in Docker: `docker compose up --build` → http://localhost:3000

| Service | URL |
|---|---|
| Frontend (dev) | http://localhost:5173 |
| API | http://localhost:8080/api |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Health | http://localhost:8080/actuator/health |
| RabbitMQ UI | http://localhost:15672 (psar / psar) |
| MinIO console | http://localhost:9001 (minioadmin / minioadmin) |

### Optional: Maven wrapper

Generate `./mvnw` once so others don't need Maven installed:

```bash
cd backend && mvn -N wrapper:wrapper
```

## Tests

```bash
cd backend && mvn verify      # unit + ArchUnit module rules + Testcontainers (needs Docker)
cd frontend && npm run lint && npm run build
```

`ModuleBoundaryTest` fails the build if a module uses another module's repository or entities.

## Roadmap

Setup → Users → Catalog & inventory → Audit & notifications → Orders & checkout → **Payments (MVP)** → Promotions → Points → Subscription → RabbitMQ & jobs → Dashboard → AI chatbot → Tests → **Launch**
