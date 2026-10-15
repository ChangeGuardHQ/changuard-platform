# Patched Kafka image

Compose and backend CI build `changuard-kafka:4.2.2-security.2` from this directory.
The base is Apache Kafka 4.2.2, pinned to its multi-platform image digest. The
broker version, startup configuration, and data-volume format stay at 4.2.2.

The original patches addressed five HIGH findings reported on October 4, 2026.
The `security.2` image also patches LZ4 Java after `CVE-2026-106451` appeared in
the vulnerability database. This image applies the following dependency patches:

| Dependency | Upstream version | Patched version | Findings addressed |
| --- | --- | --- | --- |
| libexpat | `2.8.4-r0` | `2.8.5-r0` | `CVE-2026-93990` |
| Jackson core | `2.21.6` | `2.21.7` | `CVE-2026-89407`, `CVE-2026-89425` |
| Jackson databind | `2.21.6` | `2.21.7` | `CVE-2026-91776`, `CVE-2026-91777` |
| LZ4 Java | `1.11.2` | `1.11.4` | `CVE-2026-106451` |

Nine Jackson modules in the Kafka distribution are updated to 2.21.7;
annotations remain at 2.21, matching the
[Jackson BOM](https://github.com/FasterXML/jackson-bom/blob/jackson-bom-2.21.7/pom.xml).
LZ4 1.11.4 fixes native-library extraction through predictable temporary files;
see the [maintainer's advisory](https://github.com/yawkat/lz4-java/security/advisories/GHSA-mcr4-qmvw-px4g).
The [SHA256SUMS](SHA256SUMS) file pins the artifact checksums published by Maven
Central. Jackson and LZ4 downloads are verified in a separate build stage before the JARs enter
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
on HIGH/CRITICAL findings. Local validation on October 15, 2026 reproduced one
HIGH LZ4 finding in `security.1`. Scanning `security.2` against the same database
found zero HIGH/CRITICAL vulnerabilities or secret findings, and the broker
passed its acknowledged message round trip.

CI retains JSON reports as artifacts and prints a summary of package names,
vulnerability IDs, and fixed versions to the job log. Secret matches and code
snippets are omitted from that summary. Database downloads, OS-support warnings,
and scanner-version notices are logged separately from the findings; inspect
`backend-kafka-security.json` or its summary to diagnose an exit code of 1.

When updating the patches, keep Jackson modules aligned with their BOM, update
the artifact checksums from Maven Central, and rerun the build, message test,
and image scan. A newer upstream stable image can replace this customization
once it contains the fixes and passes these same checks.
