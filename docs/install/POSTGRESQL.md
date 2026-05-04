# Installing PostgreSQL

Stressar requires **PostgreSQL 12 or later**. It has been tested with
PostgreSQL 14, 15, and 16. **PostgreSQL 16** is the recommended version for new benchmark setups.

---

## Table of Contents

- [Ubuntu / Debian](#ubuntu--debian)
- [Red Hat / CentOS / Fedora](#red-hat--centos--fedora)
- [macOS](#macos)
- [Windows](#windows)
- [Docker](#docker)
- [Verify installation](#verify-installation)
- [Post-installation setup](#post-installation-setup)
- [Benchmark-specific configuration](#benchmark-specific-configuration)

---

## Ubuntu / Debian

The PostgreSQL Global Development Group (PGDG) maintains an official APT repository that always
provides the latest releases.

```bash
# Install the PGDG repository signing key and source list
sudo apt-get install -y curl ca-certificates
sudo install -d /usr/share/postgresql-common/pgdg
curl -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc \
    --fail https://www.postgresql.org/media/keys/ACCC4CF8.asc

sudo sh -c 'echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] \
    https://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" \
    > /etc/apt/sources.list.d/pgdg.list'

sudo apt-get update

# Install PostgreSQL 16 server and client tools
sudo apt-get install -y postgresql-16 postgresql-16-contrib postgresql-client-16
```

Start and enable the service:

```bash
sudo systemctl start postgresql
sudo systemctl enable postgresql
```

---

## Red Hat / CentOS / Fedora

```bash
# Install the PGDG RPM repository
sudo dnf install -y https://download.postgresql.org/pub/repos/yum/reporpms/EL-9-x86_64/pgdg-redhat-repo-latest.noarch.rpm

# Disable the built-in module to avoid conflicts
sudo dnf -qy module disable postgresql

# Install PostgreSQL 16
sudo dnf install -y postgresql16-server postgresql16-contrib

# Initialize the database cluster
sudo /usr/pgsql-16/bin/postgresql-16-setup initdb

# Start and enable the service
sudo systemctl start postgresql-16
sudo systemctl enable postgresql-16
```

---

## macOS

**Option A — Homebrew (recommended for development):**

```bash
brew install postgresql@16
brew services start postgresql@16

# Add to PATH
echo 'export PATH="/opt/homebrew/opt/postgresql@16/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

**Option B — Postgres.app:**

Download the all-in-one application from <https://postgresapp.com/> and follow the installation
instructions. Postgres.app includes `psql` and other command-line tools.

---

## Windows

1. Download the Windows installer from
   <https://www.postgresql.org/download/windows/>.
2. Run the installer as an Administrator and follow the wizard.
3. Note the port (default: 5432) and `postgres` superuser password you set during installation.
4. After installation, open **pgAdmin** or a Command Prompt and verify (see below).

---

## Docker

For quick local testing a Docker container is the fastest approach:

```bash
# Start a PostgreSQL 16 container
docker run -d \
  --name benchmark-postgres \
  -e POSTGRES_USER=benchuser \
  -e POSTGRES_PASSWORD=benchpass \
  -e POSTGRES_DB=benchdb \
  -p 5432:5432 \
  postgres:16

# Wait for the container to be ready
docker exec benchmark-postgres pg_isready -U benchuser
```

> **Note:** Docker-based PostgreSQL is suitable for initial testing but not for production
> benchmark runs. Container I/O overhead will affect latency measurements. Use a bare-metal
> or dedicated VM installation for publishable results.

---

## Verify installation

```bash
# Check server version
psql --version   # Should report 16.x (or 12.x – 15.x for older installs)

# Confirm the service is accepting connections
pg_isready
# Expected: /var/run/postgresql:5432 - accepting connections
```

---

## Post-installation setup

### Create benchmark database and user

```bash
sudo -u postgres psql <<'EOF'
CREATE DATABASE benchdb;
CREATE USER benchuser WITH PASSWORD 'benchpass';
GRANT ALL PRIVILEGES ON DATABASE benchdb TO benchuser;
\c benchdb
GRANT ALL ON SCHEMA public TO benchuser;
EOF
```

### Enable pg_stat_statements extension

`pg_stat_statements` is required by the benchmark tool for per-query metrics.

1. Edit `postgresql.conf` (location varies by OS):

   | OS | Path |
   |---|---|
   | Ubuntu / Debian | `/etc/postgresql/16/main/postgresql.conf` |
   | Red Hat / CentOS | `/var/lib/pgsql/16/data/postgresql.conf` |
   | macOS (Homebrew) | `/opt/homebrew/var/postgresql@16/postgresql.conf` |

   Add (or uncomment) these lines:

   ```ini
   shared_preload_libraries = 'pg_stat_statements'
   pg_stat_statements.track = all
   pg_stat_statements.max = 10000
   track_io_timing = on
   track_activity_query_size = 2048
   ```

2. Restart PostgreSQL:

   ```bash
   sudo systemctl restart postgresql    # Ubuntu/Debian
   sudo systemctl restart postgresql-16 # Red Hat/CentOS
   ```

3. Enable the extension in the benchmark database:

   ```bash
   psql -U benchuser -d benchdb -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements;"
   ```

---

## Benchmark-specific configuration

For optimal and reproducible benchmark results, adjust these settings in `postgresql.conf` on a
machine with 16 GB RAM (scale proportionally for other sizes):

```ini
# Memory
shared_buffers          = 4GB
effective_cache_size    = 12GB
maintenance_work_mem    = 1GB
work_mem                = 32MB

# Write-ahead log
wal_buffers             = 16MB
min_wal_size            = 2GB
max_wal_size            = 8GB
checkpoint_completion_target = 0.9

# Planner
default_statistics_target = 100
random_page_cost        = 1.1     # SSD; use 4.0 for spinning disk
effective_io_concurrency = 200    # SSD; use 0 for spinning disk

# Parallelism
max_worker_processes    = 8
max_parallel_workers_per_gather = 4
max_parallel_workers    = 8

# Connections — set high enough for the total pool across all test scenarios
max_connections         = 400
```

Restart PostgreSQL after every `postgresql.conf` change:

```bash
sudo systemctl restart postgresql
```

### Reset statistics before each benchmark run

```bash
psql -U benchuser -d benchdb <<'EOF'
SELECT pg_stat_statements_reset();
SELECT pg_stat_reset();
EOF
```

---

## Further reading

- Official PostgreSQL documentation: <https://www.postgresql.org/docs/current/>
- `pg_stat_statements` reference: <https://www.postgresql.org/docs/current/pgstatstatements.html>
- `postgresql.conf` tuning guide: <https://www.postgresql.org/docs/current/runtime-config.html>

---

*Back to [RUNBOOK.md](../RUNBOOK.md) | [BENCHMARKING_GUIDE.md](../BENCHMARKING_GUIDE.md)*
