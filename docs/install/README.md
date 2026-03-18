# Installation Guides

Step-by-step instructions for every software component required to run the OJP Performance
Benchmark Tool.

| Component | Required for | Guide |
|---|---|---|
| [Java](JAVA.md) | All scenarios — JVM runtime for the benchmark tool | [JAVA.md](JAVA.md) |
| [Gradle](GRADLE.md) | All scenarios — build tool (wrapper included in repo) | [GRADLE.md](GRADLE.md) |
| [PostgreSQL](POSTGRESQL.md) | All scenarios — database under test | [POSTGRESQL.md](POSTGRESQL.md) |
| [pgBouncer](PGBOUNCER.md) | T3 scenario only — external connection pooler | [PGBOUNCER.md](PGBOUNCER.md) |
| [HAProxy](HAPROXY.md) | T3 scenario only — load balancer in front of pgBouncer | [HAPROXY.md](HAPROXY.md) |
| [OJP](OJP.md) | T4 scenario only — server-side pooler with JDBC driver | [OJP.md](OJP.md) |

## Quick start

For a minimal local setup (T1 baseline scenario) you only need Java, Gradle, and PostgreSQL:

1. [Install Java](JAVA.md)
2. Clone the repository and run `./gradlew installDist` (Gradle wrapper downloads Gradle automatically — see [GRADLE.md](GRADLE.md))
3. [Install PostgreSQL](POSTGRESQL.md)
4. Follow [RUNBOOK.md](../RUNBOOK.md) for database initialisation and benchmark commands.

For the full multi-scenario setup see [BENCHMARKING_GUIDE.md](../BENCHMARKING_GUIDE.md).
