# Security Policy

This repository is a portfolio and local-demo project, not a managed production
service.

## Supported Use

Security reports are welcome for the current `main` branch.

## Reporting

Open a private security advisory on GitHub if available, or contact the
repository owner before publishing details.

Please include:

- affected endpoint or file
- reproduction steps
- expected impact
- suggested fix, if known

## Local Credentials

Local Docker Compose credentials such as `commerce` / `commerce`, `test` /
`test`, and Grafana `admin` / `admin` are development-only defaults. They are
documented so the project can run from a fresh clone.

JWT signing keys under `src/main/resources/keys` are intentionally ignored by
Git. Generate local keys with the commands in [README.md](README.md) or
[docs/LOCAL_DEMO.md](docs/LOCAL_DEMO.md).

Before production use:

- replace all local credentials
- store JWT private keys in a secret manager or mounted secret
- replace mock payment and carrier integrations
- configure CORS and rate limits for the deployment environment
- run a dependency and container-image security scan

