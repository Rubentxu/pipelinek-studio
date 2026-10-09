# S5 E5.4 — TLS termination sample (nginx + Caddy)

The Studio HTTP server is plain HTTP on localhost by default. A
production deployment should terminate TLS at a reverse proxy
so the operator can:

- bind the Studio to `127.0.0.1` only (no public listener),
- expose a public TLS endpoint with a real certificate,
- enforce HSTS + modern cipher suites + HTTP/2,
- terminate client certificates (mTLS) if the operator needs a
  per-operator identity model.

The Studio itself does NOT terminate TLS (no `ktor-server-netty-tls`
in the classpath; adding it is a future S5+ pass). The two samples
below cover the two most common reverse proxies.

## Sample 1: nginx (more common on bare-metal / RHEL)

```nginx
upstream pipelinek_studio {
    # Studio binds to loopback only; we trust the proxy.
    server 127.0.0.1:7777;
    keepalive 32;
}

server {
    listen 443 ssl http2;
    server_name studio.example.com;

    # TLS cert (Let's Encrypt or operator-issued).
    ssl_certificate     /etc/letsencrypt/live/studio.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/studio.example.com/privkey.pem;

    # Modern cipher suite list.
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305;
    ssl_prefer_server_ciphers on;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 1d;
    ssl_session_tickets off;

    # HSTS (1 year, include subdomains, preload-eligible).
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "DENY" always;
    add_header Referrer-Policy "no-referrer" always;

    # Body limit (Studio has its own, this is the proxy-level cap).
    client_max_body_size 2m;

    # SSE: disable proxy buffering so events stream as they arrive.
    proxy_buffering off;
    proxy_request_buffering off;
    proxy_read_timeout 1h;
    proxy_send_timeout 1h;

    location / {
        proxy_pass http://pipelinek_studio;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}

# Redirect plain HTTP to HTTPS.
server {
    listen 80;
    server_name studio.example.com;
    return 301 https://$host$request_uri;
}
```

Reload: `nginx -s reload` (or systemd `nginx reload`).

## Sample 2: Caddy (one-file, auto-TLS via ACME)

Caddyfile:

```
studio.example.com {
    encode zstd gzip
    reverse_proxy 127.0.0.1:7777 {
        # SSE: don't buffer.
        flush_interval -1
        # Trust the studio's real client IP.
        header_up X-Real-IP {remote_host}
        header_up X-Forwarded-For {remote_host}
        header_up X-Forwarded-Proto {scheme}
    }
    # Studio's own body limit (1 MiB) is the source of truth; this is
    # the proxy-level cap.
    request_body {
        max_size 2MB
    }
}
```

Caddy fetches and renews the Let's Encrypt cert automatically.

## How to verify

After applying the proxy:

```sh
# Should be HTTPS with a valid cert + HSTS.
curl -fsS -I https://studio.example.com/health
# Should be 200 with body "ok".
curl -fsS https://studio.example.com/health
# Should NOT respond on plain HTTP.
curl -fsS http://studio.example.com/health || echo "expected 301/308 redirect"
```

The Studio itself still listens on 127.0.0.1:7777 — only the
reverse proxy is exposed. Run:

```sh
ss -tlnp | grep 7777   # should show 127.0.0.1:7777 only
```

## mTLS (operator identity)

If the operator wants per-operator certificate-based auth:

```nginx
ssl_client_certificate /etc/pipelinek-studio/ca.pem;
ssl_verify_client on;
ssl_verify_depth 2;

location / {
    if ($ssl_client_verify != SUCCESS) { return 403; }
    proxy_pass http://pipelinek_studio;
}
```

The Studio does not consume the client cert today; the proxy is the
identity boundary. A future S5+ pass can plumb `X-Client-DN` from
the proxy to the Studio via a header for per-operator experiments.

## Caveats

- SSE keep-alive: nginx defaults to 60s `proxy_read_timeout`. The
  sample above bumps it to 1h so long-running streams don't get
  cut. The Studio's own SSE handler also has a 5-minute idle close.
- TLS cert renewal: Let's Encrypt certs last 90 days. Both samples
  handle renewal automatically; verify the renewal hook is wired.
- The Studio body limit (1 MiB) and proxy body limit (2 MiB) are
  independent. The Studio's 413 + the proxy's 413 have slightly
  different bodies; operators can choose which is the source of truth.
- WebSocket / SSE through nginx requires `proxy_buffering off`.
  The sample includes it; Caddy uses `flush_interval -1`.
- TLS-only is a minimum bar. Real production should pair this with
  the SANDBOX_NETPOLICY (Studio still binds to loopback), SANDBOX_SECCOMP
  (subprocess restricted), and SANDBOX_NAMESPACE (per-run filesystem).
