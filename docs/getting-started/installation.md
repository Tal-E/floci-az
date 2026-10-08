# Installation

## Docker

Floci-AZ is distributed as a multi-arch Docker image (`linux/amd64` and `linux/arm64`).

### Image Tags

Each tag combines a **variant** (what's inside) and a **channel** (how stable).

|  | Standard | Compat (+ Azure CLI + azfloci) |
|---|---|---|
| **Release (latest)** | `latest` ✅ | `latest-compat` |
| **Release (pinned)** | `x.y.z` | `x.y.z-compat` |
| **Nightly (floating)** | `nightly` | `nightly-compat` |
| **Nightly (dated)** | `nightly-mmddyyyy` | `nightly-mmddyyyy-compat` |

For the full breakdown see [Docker Images](../configuration/docker-images.md).

### Quick Run

```bash
docker run -d --name floci-az \
  -p 4577:4577 \
  -v ./data:/app/data \
  -v /var/run/docker.sock:/var/run/docker.sock \
  floci/floci-az:latest
```

The Docker socket mount is required for Azure Functions. The entrypoint automatically
handles Docker socket group permissions on both Linux and macOS/Windows hosts.

### Choosing a tag

```yaml title="docker-compose.yml"
# Standard release: recommended for most use cases
services:
  floci-az:
    image: floci/floci-az:latest
    ports:
      - "4577:4577"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
```

Use the compat image if your workflow requires the Azure CLI or `azfloci` available inside the container:

```yaml title="docker-compose.yml"
services:
  floci-az:
    image: floci/floci-az:latest-compat
    ports:
      - "4577:4577"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
```

Standard and compat have identical startup time and memory footprint.

---

## Manual Build (Development)

Requirements: **Java 25**, **Maven 3.9+**, **Docker**

### JVM build

```bash
./mvnw package -DskipTests
java -jar target/quarkus-app/quarkus-run.jar
```

### Native build (fastest startup)

```bash
./mvnw package -Dnative -DskipTests
./target/*-runner
```

Native builds require GraalVM / Mandrel with native-image support.

### Docker build (local)

```bash
# JVM image
docker build -f Dockerfile -t floci-az:dev .

# Native image (single-arch, current machine)
docker build -f Dockerfile.native -t floci-az:dev-native .
```
