# ─────────────────────────────────────────────
#  Stage 1 – Build the jar with Maven
# ─────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# POM first so dependencies download in a cacheable layer
COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

# ─────────────────────────────────────────────
#  Stage 2 – Runtime (JRE only), non-root
# ─────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy AS runtime

# docker-compose-wait blocks startup until PostgreSQL accepts connections
COPY --from=ghcr.io/ufoscout/docker-compose-wait:2.12.0 /wait /wait

RUN groupadd --system vault && useradd --system --gid vault --no-create-home vault \
    && mkdir -p /data/vault-files /logs \
    && chown -R vault:vault /data /logs

WORKDIR /app
COPY --from=builder /build/target/corroboro-vault-*.jar /app/app.jar

USER vault
EXPOSE 8090

# No public port is ever published for this service — it is reachable only from
# the main backend over an internal Docker network (see File-Storage.md).
CMD ["/bin/sh", "-c", "/wait && exec java -jar /app/app.jar"]
