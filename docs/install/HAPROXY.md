# Installing HAProxy (Load Balancer)

HAProxy is a high-performance TCP/HTTP load balancer. It is used in the **T3 scenario** of this
benchmark to distribute JDBC connections across the three pgBouncer proxy instances.

> **Note:** HAProxy is **only required for the T3 (pgBouncer) scenario**. The T4 (OJP) scenario
> uses client-side load balancing built into the OJP JDBC driver — no external load balancer is
> needed.

The benchmark requires **HAProxy 2.8 or later**.

---

## Table of Contents

- [Ubuntu / Debian](#ubuntu--debian)
- [Red Hat / CentOS / Fedora](#red-hat--centos--fedora)
- [macOS](#macos)
- [Docker](#docker)
- [Build from source](#build-from-source)
- [Verify installation](#verify-installation)
- [Configuration for benchmarking](#configuration-for-benchmarking)
- [Start HAProxy](#start-haproxy)
- [Verify connectivity](#verify-connectivity)

---

## Ubuntu / Debian

Ubuntu 22.04 LTS ships HAProxy 2.4 in its default repositories. Use the HAProxy PPA for a newer
version.

**Via HAProxy PPA (recommended):**

```bash
sudo apt-get install -y software-properties-common
sudo add-apt-repository -y ppa:vbernat/haproxy-2.8
sudo apt-get update
sudo apt-get install -y haproxy=2.8.*

haproxy -v   # Must report 2.8 or later
```

**Via default repository (Ubuntu 22.04 — HAProxy 2.4):**

```bash
sudo apt-get update
sudo apt-get install -y haproxy
haproxy -v
```

> If the installed version is below 2.8, use the PPA method above or build from source.

---

## Red Hat / CentOS / Fedora

```bash
# Fedora
sudo dnf install -y haproxy
haproxy -v

# CentOS / RHEL — enable EPEL first
sudo dnf install -y epel-release
sudo dnf install -y haproxy
haproxy -v
```

---

## macOS

```bash
brew install haproxy
haproxy -v
```

---

## Docker

```bash
docker run -d \
  --name haproxy-lb \
  -v /path/to/haproxy.cfg:/usr/local/etc/haproxy/haproxy.cfg:ro \
  -p 6432:6432 \
  haproxy:2.8

# Reload configuration
docker kill -s HUP haproxy-lb
```

---

## Build from source

```bash
# Install build dependencies
sudo apt-get install -y build-essential libssl-dev libpcre3-dev zlib1g-dev liblua5.4-dev

# Download and extract the latest 2.8 release
curl -LO https://www.haproxy.org/download/2.8/src/haproxy-2.8.9.tar.gz
tar xzf haproxy-2.8.9.tar.gz
cd haproxy-2.8.9

# Compile and install
make TARGET=linux-glibc USE_OPENSSL=1 USE_PCRE=1 USE_ZLIB=1 USE_LUA=1
sudo make install

haproxy -v
```

---

## Verify installation

```bash
haproxy -v
# Expected: HAProxy version 2.8.x (or later)
```

---

## Configuration for benchmarking

HAProxy is configured via `/etc/haproxy/haproxy.cfg`. The following configuration sets up TCP
pass-through load balancing across three pgBouncer instances using the `leastconn` algorithm.

```
global
    maxconn 10000
    log /dev/log local0

defaults
    mode    tcp
    log     global
    option  tcplog
    timeout connect 5s
    timeout client  300s
    timeout server  300s

frontend pgbouncer_front
    bind *:6432
    default_backend pgbouncer_back

backend pgbouncer_back
    balance leastconn
    server proxy1 <PROXY1_IP>:6432 check inter 2s
    server proxy2 <PROXY2_IP>:6432 check inter 2s
    server proxy3 <PROXY3_IP>:6432 check inter 2s
```

Replace `<PROXY1_IP>`, `<PROXY2_IP>`, and `<PROXY3_IP>` with the actual IP addresses of the
three pgBouncer machines.

**Configuration notes:**

| Setting | Value | Explanation |
|---|---|---|
| `mode tcp` | TCP | Pass-through mode — HAProxy does not parse or modify the PostgreSQL wire protocol |
| `balance leastconn` | Least connections | Routes new connections to the instance with fewest active connections |
| `maxconn 10000` | 10 000 | Global limit; must be ≥ `max_client_conn` across all pgBouncer instances |
| `timeout client/server` | 300 s | Long-lived JDBC connections require a timeout longer than the benchmark duration |
| `check inter 2s` | Health check every 2 s | Removes unhealthy instances from rotation without impacting in-flight connections |

---

## Start HAProxy

```bash
# Check configuration syntax
sudo haproxy -c -f /etc/haproxy/haproxy.cfg

# Start and enable the systemd service
sudo systemctl start haproxy
sudo systemctl enable haproxy

# Reload configuration without dropping connections
sudo systemctl reload haproxy
# or
sudo haproxy -sf $(cat /var/run/haproxy.pid) -f /etc/haproxy/haproxy.cfg
```

---

## Verify connectivity

```bash
# Connect through HAProxy (port 6432) to pgBouncer then to PostgreSQL
psql -h <LB_IP> -p 6432 -U benchuser -d benchdb -c "SELECT version();"

# Check HAProxy statistics
echo "show stat" | socat stdio /var/run/haproxy/admin.sock
```

---

## Further reading

- Official HAProxy documentation: <https://docs.haproxy.org/>
- HAProxy TCP mode: <https://docs.haproxy.org/2.8/configuration.html#4-mode>
- HAProxy `leastconn` algorithm: <https://docs.haproxy.org/2.8/configuration.html#4-balance>

---

*Back to [RUNBOOK.md](../RUNBOOK.md) | [BENCHMARKING_GUIDE.md](../BENCHMARKING_GUIDE.md)*
