# Patched Kafka image

Compose and backend CI build `changuard-kafka:4.2.2-security.1` from this directory.
The base is Apache Kafka 4.2.2, pinned to its multi-platform image digest. The
broker version, startup configuration, and data-volume format stay at 4.2.2.

The upstream image still contains five HIGH findings as of October 4, 2026.
This image applies the following stable dependency patches:

| Dependency | Upstream version | Patched version | Findings addressed |
| --- | --- | --- | --- |
| libexpat | `2.8.4-r0` | `2.8.5-r0` | `CVE-2026-93990` |
| Jackson core | `2.21.6` | `2.21.7` | `CVE-2026-89407`, `CVE-2026-89425` |
| Jackson databind | `2.21.6` | `2.21.7` | `CVE-2026-91776`, `CVE-2026-91777` |

Nine Jackson modules in the Kafka distribution are updated to 2.21.7;
annotations remain at 2.21, matching the
[Jackson BOM](https://github.com/FasterXML/jackson-bom/blob/jackson-bom-2.21.7/pom.xml).
The [SHA256SUMS](SHA256SUMS) file pins the artifact checksums published by Maven
Central. Downloads are verified in a separate build stage before the JARs enter
the runtime image. Old JARs are removed, and the image still runs as `appuser`.

Apache's prebuilt JVM class-data archives were generated with the old JARs.
The Dockerfile removes those archives and their startup options so both storage
formatting and broker startup use the patched libraries. This gives up that
startup optimization; it does not change the Kafka data format.

From the repository root, build and test with:

```sh
docker compose up --build --wait connector-service
sh backend/docker/kafka/smoke-test.sh
```

The smoke test creates a temporary topic, publishes a JSON message with broker
acknowledgement, verifies the consumed payload, and deletes the topic. It accepts
Docker Compose options, for example `--project-name validation --env-file /tmp/validation.env`,
to test an isolated stack.

Backend CI builds this image, runs the message test, and scans the resulting
image with Trivy 0.70.0. The scan includes unfixed vulnerabilities and fails CI
on HIGH/CRITICAL findings. Local validation on October 4, 2026
found zero HIGH/CRITICAL vulnerabilities or secret findings in the patched image.

When updating the patches, keep Jackson modules aligned with their BOM, update
the artifact checksums from Maven Central, and rerun the build, message test,
and image scan. A newer upstream stable image can replace this customization
once it contains the fixes and passes these same checks.
