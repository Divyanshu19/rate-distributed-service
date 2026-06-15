--[[
  Token Bucket Rate Limiter — Atomic Lua Script
  ═════════════════════════════════════════════
  Models a bucket that fills with tokens at a constant rate.
  Each request consumes one token. Requests are rejected when the bucket is empty.

  Key property: allows SHORT BURSTS (up to bucket capacity) while enforcing
  a long-term average rate. This is the core difference from Sliding Window:

    Sliding Window: smooth, accurate, no burst tolerance
    Token   Bucket: allows burst up to capacity, then drains at refill rate

  Data structure: Redis HASH (two fields per key)
  ┌──────────────────────────────────────────┐
  │  Field         │  Value                  │
  ├────────────────┼─────────────────────────┤
  │  tokens        │  7.333  (float — exact) │
  │  lastRefillTime│  1700000000000  (epoch ms) │
  └──────────────────────────────────────────┘

  Refill formula (computed on every request):
    elapsedMs    = currentTimeMs - lastRefillTime
    tokensToAdd  = elapsedMs × refillRatePerMs
    tokens       = min(capacity, tokens + tokensToAdd)

  Example — max 10 req / 60s:
    refillRatePerMs = 10 / (60 × 1000) = 0.0001667 tokens/ms
    After 6000ms idle: tokensToAdd = 6000 × 0.0001667 = 1.0 token restored

  KEYS[1]  = hash key          e.g. "rate_limit:tenant-acme:/api/v1/orders"
  ARGV[1]  = currentTimeMs     current epoch time in milliseconds
  ARGV[2]  = capacity          maxRequests — bucket size ceiling
  ARGV[3]  = refillRatePerMs   tokens added per millisecond
  ARGV[4]  = ttlSeconds        key TTL = 2 × windowSizeSeconds

  Returns:
    >= 0  → request ALLOWED;  value = remaining whole tokens after this request
      -1  → request REJECTED; bucket is empty (< 1 token available)
--]]

local key             = KEYS[1]
local now             = tonumber(ARGV[1])
local capacity        = tonumber(ARGV[2])
local refillRatePerMs = tonumber(ARGV[3])
local ttl             = tonumber(ARGV[4])

-- ── Read current bucket state ────────────────────────────────────────────────
local storedTokens    = redis.call('HGET', key, 'tokens')
local storedLastRefill = redis.call('HGET', key, 'lastRefillTime')

local tokens
local lastRefillTime

if storedTokens == false then
    -- ── First request for this key: start with a FULL bucket ─────────────────
    -- This is the burst allowance — new tenants get their full quota immediately.
    tokens         = capacity
    lastRefillTime = now
else
    -- ── Existing bucket: refill based on elapsed time ────────────────────────
    tokens         = tonumber(storedTokens)
    lastRefillTime = tonumber(storedLastRefill)

    local elapsedMs   = now - lastRefillTime
    local tokensToAdd = elapsedMs * refillRatePerMs

    -- Clamp to capacity — bucket cannot overflow above its maximum.
    tokens = math.min(capacity, tokens + tokensToAdd)
end

-- ── Decision ─────────────────────────────────────────────────────────────────
if tokens >= 1 then
    -- Consume exactly one token for this request.
    tokens = tokens - 1

    -- Persist the updated bucket state.
    -- Store tokens as a precise float string so fractional accumulation
    -- is preserved across requests (truncating would lose refill progress).
    redis.call('HSET', key, 'tokens', tostring(tokens), 'lastRefillTime', tostring(now))
    redis.call('EXPIRE', key, ttl)

    -- Return remaining WHOLE tokens (floor), because partial tokens
    -- cannot satisfy a future request — they are purely refill progress.
    return math.floor(tokens)

else
    -- Bucket is empty (tokens < 1). Do NOT update state.
    --
    -- Critical: rejected requests must NOT advance lastRefillTime.
    -- If we updated it, a flood of rejected requests would continuously
    -- reset the refill clock, meaning tokens would never accumulate.
    -- By leaving state unchanged, the refill clock keeps ticking from
    -- the last SUCCESSFUL request regardless of how many rejections follow.
    return -1

end
