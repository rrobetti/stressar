# Installing pgBouncer

pgBouncer is a lightweight connection pooler for PostgreSQL. It is used in the **T3 scenario** of
this benchmark as an external proxy between the load generator and the PostgreSQL database.

The benchmark requires **pgBouncer 1.21 or later**.

---

## Table of Contents

- [Ubuntu / Debian](#ubuntu--debian)
- [Red Hat / CentOS / Fedora](#red-hat--centos--fedora)
- [macOS](#macos)
- [Docker](#docker)
- [Build from source](#build-from-source)
- [Verify installation](#verify-installation)
- [Basic configuration for benchmarking](#basic-configuration-for-benchmarking)
- [Start pgBouncer](#start-pgbouncer)
- [Verify connectivity](#verify-connectivity)

---

## Ubuntu / Debian

The pgBouncer package is available in the standard Ubuntu repositories and via the PGDG APT
repository. The PGDG repository is preferred because it usually contains a newer version.

**Via PGDG APT repository (recommended):**

```bash
# If you have not already added the PGDG repository, follow the steps in POSTGRESQL.md first.
# Then:
sudo apt-get update
sudo apt-get install -y pgbouncer

pgbouncer --version   # Must report 1.21 or later
```

**Via default Ubuntu repository (may be older):**

```bash
sudo apt-get update
sudo apt-get install -y pgbouncer
pgbouncer --version
```

---

## Red Hat / CentOS / Fedora

```bash
# Ensure the PGDG RPM repository is configured (see POSTGRESQL.md)
sudo dnf install -y pgbouncer

pgbouncer --version   # Must report 1.21 or later
```

---

## macOS

```bash
brew install pgbouncer
pgbouncer --version
```

---

## Docker

```bash
docker run -d \
  --name pgbouncer \
  -e DATABASE_URL="postgresql://benchuser:benchpass@<DB_IP>:5432/benchdb" \
  -p 6432:6432 \
  edoburu/pgbouncer
```

---

## Build from source

If the package version in your distribution is older than 1.21, build from source:

```bash
# Install build dependencies
sudo apt-get install -y build-essential libevent-dev libssl-dev pkg-config

# Download and extract the latest release
curl -LO https://www.pgbouncer.org/downloads/files/1.23.1/pgbouncer-1.23.1.tar.gz
tar xzf pgbouncer-1.23.1.tar.gz
cd pgbouncer-1.23.1

# Configure, compile, and install
./configure --prefix=/usr/local
make
sudo make install

pgbouncer --version
```

---

## Verify installation

```bash
pgbouncer --version
# Expected: PgBouncer 1.21.0 (or later)
```

---

## Basic configuration for benchmarking

pgBouncer is configured via `/etc/pgbouncer/pgbouncer.ini` (or a custom path you pass on the
command line).

### `/etc/pgbouncer/pgbouncer.ini`

```ini
[databases]
benchdb = host=<DB_IP> port=5432 dbname=benchdb

[pgbouncer]
listen_port          = 6432
listen_addr          = *
auth_type            = md5
auth_file            = /etc/pgbouncer/userlist.txt
pool_mode            = transaction
default_pool_size    = 16
max_client_conn      = 2000
reserve_pool_size    = 4
reserve_pool_timeout = 5
server_tls_sslmode   = disable
ignore_startup_parameters = extra_float_digits
```

**Key settings explained:**

| Setting | Value | Explanation |
|---|---|---|
| `pool_mode` | `transaction` | Release server connection after each transaction (required for multiplexing) |
| `default_pool_size` | `16` | Backend connections per database/user pair; 3 instances × 16 = 48 total |
| `max_client_conn` | `2000` | Maximum simultaneous client connections across all pools |
| `ignore_startup_parameters` | `extra_float_digits` | Required when using the PostgreSQL JDBC driver |

### `/etc/pgbouncer/userlist.txt`

Create the password file. pgBouncer accepts MD5-hashed passwords in the format
`"username" "md5<hash>"` or plain-text passwords (for testing only):

```bash
# Generate the MD5 hash: md5(password + username)
echo -n "benchpassbenchuser" | md5sum
# Example output: 1a2b3c4d...
```

```
# /etc/pgbouncer/userlist.txt
"benchuser" "md51a2b3c4d..."
```

For quick testing you can use a plain-text password (not recommended for production):

```
"benchuser" "benchpass"
```

---

## Start pgBouncer

```bash
# Start and enable the systemd service (if installed via package manager)
sudo systemctl start pgbouncer
sudo systemctl enable pgbouncer

# Or start manually with a custom config file
pgbouncer -d /etc/pgbouncer/pgbouncer.ini
```

---

## Verify connectivity

```bash
# Connect through pgBouncer (port 6432) instead of directly to PostgreSQL (port 5432)
psql -h 127.0.0.1 -p 6432 -U benchuser -d benchdb -c "SELECT version();"

# Check the pgBouncer admin console
psql -h 127.0.0.1 -p 6432 -U pgbouncer -d pgbouncer -c "SHOW POOLS;"
```

---

## Further reading

- Official pgBouncer documentation: <https://www.pgbouncer.org/usage.html>
- pgBouncer configuration reference: <https://www.pgbouncer.org/config.html>
- pgBouncer and JDBC prepared statements: <https://www.pgbouncer.org/faq.html>

---

*Back to [RUNBOOK.md](../RUNBOOK.md) | [BENCHMARKING_GUIDE.md](../BENCHMARKING_GUIDE.md)*
