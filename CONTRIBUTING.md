# Contributing

This repository is organized around small, verifiable feature slices.

## Local Setup

Follow [docs/LOCAL_DEMO.md](docs/LOCAL_DEMO.md) to generate local JWT keys, start
Docker dependencies, run the app, and seed OpenSearch.

## Development Checks

Run tests before opening a pull request:

```bash
./gradlew test
```

For migration changes, also validate Flyway against a local database:

```bash
FLYWAY_URL=jdbc:postgresql://localhost:5432/commerce \
FLYWAY_USER=commerce \
FLYWAY_PASSWORD=commerce \
./gradlew flywayValidate
```

## Commit Style

Prefer focused commits with a conventional prefix:

- `feat:` for user-visible behavior
- `fix:` for bug fixes
- `chore:` for maintenance and docs
- `test:` for test-only changes

## Public Documentation

When adding a new major capability, update the relevant public doc:

- [README.md](README.md)
- [docs/TECH_STACK.md](docs/TECH_STACK.md)
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- [docs/API_OVERVIEW.md](docs/API_OVERVIEW.md)

