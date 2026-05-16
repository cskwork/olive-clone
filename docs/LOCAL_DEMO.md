# Local Demo

This guide starts the public catalog UI and API from a fresh clone.

## 1. Generate Local JWT Keys

The application signs local JWTs with files under `src/main/resources/keys`.
They are intentionally ignored by Git.

```bash
mkdir -p src/main/resources/keys
openssl genpkey -algorithm RSA -out src/main/resources/keys/app.key -pkeyopt rsa_keygen_bits:2048
openssl rsa -in src/main/resources/keys/app.key -pubout -out src/main/resources/keys/app.pub
```

## 2. Start Dependencies

```bash
docker compose up -d postgres redis localstack opensearch
docker compose ps
```

Expected default ports:

| Service | URL |
| --- | --- |
| Postgres | `localhost:5432` |
| Redis | `localhost:6379` |
| LocalStack S3 | `http://localhost:4566` |
| OpenSearch | `http://localhost:9200` |

## 3. Run the Application

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

The default app port is `8080`.

## 4. Seed Search Index

Flyway seeds product data automatically. OpenSearch still needs a one-time local
reindex.

```bash
./gradlew reindexProducts --args='--spring.profiles.active=local,reindex --server.port=8082'
```

Expected log line:

```text
Reindex completed: 13 products indexed.
```

## 5. Open the Demo

- UI: http://localhost:8080/products
- Product API: http://localhost:8080/api/products?size=20
- Search API: http://localhost:8080/api/search/products?keyword=%EC%84%A0%ED%81%AC%EB%A6%BC&page=0&size=5
- Health: http://localhost:8080/actuator/health
- Metrics: `/actuator/prometheus` is protected by default; configure scrape
  credentials or a local-only security override if you want to expose it during
  a metrics demo.

The catalog should show 13 demo products.
Each demo product uses a checked-in local catalog image under
`src/main/resources/static/images/products/`, so the UI is fully visible without
external image hosting.
The demo images are generated assets. See
[ASSET_PROVENANCE.md](ASSET_PROVENANCE.md).

## 6. Optional Observability Stack

```bash
docker compose up -d prometheus grafana
```

- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000
- Local Grafana credentials: `admin` / `admin`

## Troubleshooting

### JWT key files missing

If boot fails with `classpath:keys/app.key` or `classpath:keys/app.pub`, rerun the
key generation commands in step 1.

### Port already in use

Stop the conflicting local service or override Spring Boot with:

```bash
./gradlew bootRun --args='--spring.profiles.active=local --server.port=8081'
```

If Docker service ports conflict, either stop the conflicting containers or add a
small Docker Compose override for the occupied port.

### Empty search results

Run the reindex task again after OpenSearch is healthy:

```bash
./gradlew reindexProducts --args='--spring.profiles.active=local,reindex --server.port=8082'
```
