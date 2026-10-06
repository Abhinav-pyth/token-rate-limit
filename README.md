# Rate Limiting Demo — Token Bucket (Spring Boot)

A minimal, dependency-free demo of **API rate limiting using the Token Bucket algorithm**
built with Spring Boot 3 (Java 17). No external libraries like Guava/Bucket4j are needed —
the bucket is implemented from scratch.

## How the Token Bucket works

Each client (identified by IP / `X-Forwarded-For`) owns a bucket:

- **Capacity** — the bucket holds at most `N` tokens (this is the allowed *burst* size).
- **Refill rate** — tokens are added continuously at `R` tokens/second (the *sustained* rate).
- Every request consumes **1 token**. If the bucket is empty → `429 Too Many Requests`.

Tokens are refilled *lazily*: on each access we compute how much time passed since the last
refill and add `elapsed × R` tokens (capped at capacity). This avoids background schedulers.

```
capacity = 5, refill = 1 token/sec

t=0s   : 5 requests pass instantly (burst), 6th+ get 429
t=1s   : 1 token back -> one more request passes
idle   : bucket never exceeds 5 tokens
```

## Project structure

```
src/main/java/com/example/ratelimit
├── RateLimitApplication.java            # Spring Boot entry point
├── bucket
│   ├── TokenBucket.java                 # thread-safe lazy-refill token bucket
│   └── TokenBucketRegistry.java         # one bucket per client key (ConcurrentHashMap)
├── config
│   ├── RateLimitProperties.java         # bound to `rate-limit.*` in application.yml
│   ├── RateLimitConfig.java             # wires the registry bean
│   └── WebConfig.java                   # registers the interceptor for /api/**
├── interceptor
│   └── RateLimitInterceptor.java        # consumes a token per request, returns 429 + headers
└── controller
    └── DemoController.java              # GET /api/hello — the protected endpoint
```

## Configuration (`application.yml`)

```yaml
rate-limit:
  enabled: true
  capacity: 5                  # max burst (bucket size, in tokens)
  refill-tokens-per-second: 1  # sustained rate (tokens per second)
```

## Run it

```bash
mvn spring-boot:run
# or
mvn clean package && java -jar target/rate-limit-token-bucket-0.0.1-SNAPSHOT.jar
```

Fire a burst of requests:

```bash
for i in $(seq 1 8); do
  curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/hello
done
# 200 x5, then 429 x3
```

Wait ~1–2 seconds and try again — a refilled token lets the next request through.

### Response headers

| Header                 | Meaning                                        |
|------------------------|------------------------------------------------|
| `X-RateLimit-Limit`    | Bucket capacity (max burst)                    |
| `X-RateLimit-Remaining`| Tokens left after this request                 |
| `X-RateLimit-Reset`    | Seconds until the next token is available      |
| `Retry-After`          | On 429: suggested wait in seconds              |

Example 429 body:

```json
{
  "error": "Too Many Requests",
  "message": "Rate limit exceeded. Try again in ~1 second(s).",
  "retryAfterSeconds": 1,
  "limit": 5,
  "refillTokensPerSecond": 1.0
}
```

## Tests

```bash
mvn test
```

- `TokenBucketTest` — unit tests: burst limits, refill over time, capacity cap,
  multi-token consumption, thread safety under 20 concurrent threads, invalid config.
- `RateLimitIntegrationTest` — MockMvc tests: burst then 429 with headers,
  per-client isolation, recovery after refill.

## Notes & possible extensions

- Buckets live in memory; for a distributed system use Redis (e.g. Lua script) or
  Bucket4j with a Hazelcast/Redis backend.
- For production APIs consider the `RateLimit` standard headers (`RateLimit-Limit`, etc.).
- Key strategy can be switched from IP to API key / user ID inside
  `RateLimitInterceptor#resolveClientKey`.

---

## Deploying to Vercel — read this first

**Vercel does not support Spring Boot / long-running Java servers.** There is no official
`@vercel/java` builder (the package is not published on npm), and Vercel's runtime model is
static sites + short-lived Serverless/Edge functions. A Spring Boot app needs a persistent JVM,
so a direct deploy will fail or misbehave. Additionally, this demo's token buckets are
**in-memory**, which cannot work correctly across stateless, auto-scaled serverless invocations.

### Option A (recommended): deploy the jar/container elsewhere
A `Dockerfile` is included in this repo. It works as-is on **Railway, Fly.io, Render,
Koyeb, AWS App Runner/ECS, Google Cloud Run**, or any Docker host — all of which run a
long-lived container so the token bucket behaves correctly:

```bash
mvn package && docker build -t rate-limit-demo . && docker run -p 8080:8080 rate-limit-demo
```
- Railway / Render: connect the GitHub repo, pick "Docker" as the build provider.
- Fly.io: `fly launch` (it detects the Dockerfile) → `fly deploy`.
- Cloud Run / App Runner: point them at the image built from this Dockerfile.
- The app honors the injected `PORT` env var (`server.port: ${PORT:8080}`), so no config changes needed.

### Option B: keep the frontend on Vercel, backend elsewhere ("hybrid")
If you specifically want a Vercel URL, deploy a static/React frontend to Vercel and proxy API
calls to the Spring Boot container hosted per Option A. In `vercel.json`, rewrites can forward
`/api/*` to your backend's public URL:

```json
{
  "rewrites": [
    { "source": "/api/(.*)", "destination": "https://rate-limit-demo.onrender.com/api/$1" }
  ]
}
```

### Option C: force it onto Vercel Serverless (not recommended for this demo)
The only way to run Java on Vercel today is via custom-runtime Serverless Functions, which means
restructuring the app into request-scoped functions, losing the embedded-server model, and moving
rate-limit state to an external store such as **Upstash Redis** (buckets in memory would reset on
every cold start). If you go this route, consider rewriting the limiter with Bucket4j + a Redis
backend, or simply use Vercel's own Edge Config/KV-based middleware for limiting instead.
