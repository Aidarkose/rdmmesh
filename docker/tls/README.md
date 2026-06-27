# docker/tls — TLS-материал для HTTPS pull rdmmesh → OM

`rdmmesh-service` ходит в OM REST по HTTPS через TLS-терминатор `om-tls` (см.
`om-catalog/tls-gateway/`). Для проверки цепочки JVM использует truststore с внутренним CA.

| Файл | В git | Назначение |
|------|-------|------------|
| `rootCA.crt` | да | публичный сертификат внутреннего CA |
| `om-ca-truststore.p12` | нет (`*.p12` в .gitignore) | штатный `cacerts` JDK + внутренний CA; **воспроизводим** |

Truststore не коммитится (политика — кейстораны не в репо), но детерминированно
собирается из `rootCA.crt`:

```bash
docker run --rm -v "$PWD":/work eclipse-temurin:21-jre bash -c '
  cp "$JAVA_HOME/lib/security/cacerts" /work/om-ca-truststore.p12
  keytool -importcert -noprompt -trustcacerts -alias bank-internal-root-ca \
    -file /work/rootCA.crt -keystore /work/om-ca-truststore.p12 \
    -storepass changeit -storetype PKCS12'
```

Подключается в `docker/docker-compose.yml` (сервис `rdmmesh-service`): volume-mount в
`/opt/rdmmesh/tls/` + `JAVA_OPTS -Djavax.net.ssl.trustStore=…`. В проде — корпоративный
CA вместо self-signed.
