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

## Deploying to Vercel

Vercel supports Java via community builders (`@vercel/java`). A `vercel.json` is included in this repo.

### Steps
1. Push this project to GitHub/GitLab/Bitbucket.
2. Import the repo at https://vercel.com/new (or run `npx vercel` from this folder).
3. The included `vercel.json` tells Vercel to build with `@vercel/java` using `pom.xml`.
4. After deployment, test: `curl -i https://<your-app>.vercel.app/api/hello`

### Important caveats for THIS app on Vercel
- **Serverless = cold starts.** Spring Boot can take 5–10s to boot on the first request after idle.
- **In-memory token buckets do NOT persist** across invocations and are not shared between instances.
  Rate limiting will behave inconsistently on serverless. For production-grade limiting, back the
  bucket with a fast global store such as **Upstash Redis** (see alternative below).

### Alternative (recommended): deploy on a persistent host
Any of these runs the same jar with a long-lived JVM, so the token bucket works correctly:
```bash
# Render / Railway / Fly.io / any Docker host — Dockerfile:
FROM eclipse-temurin:17-jre
COPY target/rate-limit-token-bucket-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java","-jar","/app.jar"]
```
Build first with `mvn package`, then point the host at the Dockerfile or use their native Spring Boot support.
