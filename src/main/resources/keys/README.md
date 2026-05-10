# JWT 서명 키 (RS256)

이 디렉토리에는 **로컬/개발용** RSA 2048 keypair 가 위치한다 (`app.key`, `app.pub`).
실제 키 파일은 `.gitignore` 처리되어 있으며 (OLV-005), 운영 환경은 Secrets
Manager 또는 K8s Secret 마운트로 주입한다.

## 키 생성

```bash
cd src/main/resources/keys
openssl genpkey -algorithm RSA -out app.key -pkeyopt rsa_keygen_bits:2048
openssl rsa -in app.key -pubout -out app.pub
```

`app.key` 는 PKCS#8 PEM (`-----BEGIN PRIVATE KEY-----`),
`app.pub` 는 X.509 SubjectPublicKeyInfo PEM (`-----BEGIN PUBLIC KEY-----`).

## 설정 매핑

`application.yml`:

```yaml
olive:
  security:
    jwt:
      issuer: olive-commerce
      access-ttl: PT30M
      refresh-ttl: P14D
      private-key-location: classpath:keys/app.key
      public-key-location:  classpath:keys/app.pub
```

운영에서는 `private-key-location: file:/run/secrets/olive-jwt.key` 와 같이
외부 경로로 주입한다.

## 키 회전

OLV-005 시점에는 단일 keypair. 향후 회전 도입 시 `JWKSource` 다중 키 + `kid` 헤더로 확장한다 (`llm-wiki/98-security.md`).
