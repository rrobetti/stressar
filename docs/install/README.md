# Installation Guides

Step-by-step instructions for the software components commonly used to run Stressar JDBC workload benchmarks.

## Version reference (tools used in the bundled reference scenarios)

| Tool / component | Version used / required                                      |
|---|--------------------------------------------------------------|
| OJP Server + OJP JDBC Driver | **0.4.21-beta**                                              |
| Java (benchmark tool / control / loadgen) | **11+** (recommended LTS: 21)                                |
| Java (OJP Server nodes) | **24+**                                                      |
| Gradle (via wrapper) | **8.8** pinned in `gradle/wrapper/gradle-wrapper.properties` |
| PostgreSQL | **12+** (tested: 14/15/16, recommended: 16)                  |
| pgBouncer | **1.21+**                                                    |
| HAProxy | **2.8+**                                                     |

For Ansible-specific tooling versions (for automation runs), see [ansible/README.md](../../ansible/README.md#prerequisites).

---

| Component | Common use case | Guide |
|---|---|---|
| [Java](JAVA.md) | All scenarios — JVM runtime for the benchmark tool | [JAVA.md](JAVA.md) |
| [Gradle](GRADLE.md) | All scenarios — build tool (wrapper included in repo) | [GRADLE.md](GRADLE.md) |
| [PostgreSQL](POSTGRESQL.md) | All scenarios — database under test | [POSTGRESQL.md](POSTGRESQL.md) |
| [pgBouncer](PGBOUNCER.md) | T3 scenario only — external connection pooler | [PGBOUNCER.md](PGBOUNCER.md) |
| [HAProxy](HAPROXY.md) | T3 scenario only — load balancer in front of pgBouncer | [HAPROXY.md](HAPROXY.md) |
| [OJP Server](OJP.md) | T4 scenario only — server-side pooler (proxy nodes) | [OJP.md](OJP.md) |
| [OJP JDBC Driver](OJP_JDBC_DRIVER.md) | T4 scenario only — JDBC driver (load generator) | [OJP_JDBC_DRIVER.md](OJP_JDBC_DRIVER.md) |

## Quick start

For a minimal local JDBC benchmark setup you only need Java, Gradle, and PostgreSQL:

1. [Install Java](JAVA.md)
2. Clone the repository and run `./gradlew installDist` (Gradle wrapper downloads Gradle automatically — see [GRADLE.md](GRADLE.md))
3. [Install PostgreSQL](POSTGRESQL.md)
4. Follow [RUNBOOK.md](../RUNBOOK.md) for database initialisation and benchmark commands.

For the full bundled reference suite, including proxy and pooler topologies, see [BENCHMARKING_GUIDE.md](../BENCHMARKING_GUIDE.md).
