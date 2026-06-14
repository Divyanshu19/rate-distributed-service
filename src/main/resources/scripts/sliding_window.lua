--[[
  Sliding Window Rate Limiter — Atomic Lua Script
  ═══════════════════════════════════════════════
  Runs entirely inside Redis, so all steps execute as one atomic transaction.
  No other client can interleave between the ZREMRANGEBYSCORE and ZADD —
  this is what makes the algorithm correct under concurrency.

  Data structure: Redis Sorted Set (ZSET)
    - Member : unique request ID  → "<currentTimeMs>-<uuid>"
    - Score  : request timestamp in milliseconds

  The "window" is the range: (currentTimeMs - windowSizeMs, currentTimeMs]
  We keep only entries whose score falls inside that range.

  KEYS[1]  = rate limit key       e.g. "rate_limit:tenant-acme:/api/v1/orders"
  ARGV[1]  = currentTimeMs        current epoch time in milliseconds (string)
  ARGV[2]  = windowStartMs        currentTimeMs - (windowSizeSeconds * 1000)
  ARGV[3]  = maxRequests          ceiling for this rule
  ARGV[4]  = ttlSeconds           key expiry = 2 × windowSize (safety margin)
  ARGV[5]  = member               unique ID for this specific request

  Returns:
    >= 0   → request ALLOWED;  value = remaining capacity after this request
      -1   → request REJECTED; window is full, do not record the request
--]]

local key          = KEYS[1]
local now          = ARGV[1]          -- kept as string for ZADD score
local windowStart  = ARGV[2]          -- kept as string for ZREMRANGEBYSCORE
local maxRequests  = tonumber(ARGV[3])
local ttl          = tonumber(ARGV[4])
local member       = ARGV[5]

-- ── Step 1 ──────────────────────────────────────────────────────────────────
-- Evict all requests that have slid outside the window.
-- ZREMRANGEBYSCORE removes members whose score is in range [-inf, windowStart].
-- After this, only requests within the last windowSizeSeconds remain.
redis.call('ZREMRANGEBYSCORE', key, '-inf', windowStart)

-- ── Step 2 ──────────────────────────────────────────────────────────────────
-- Count how many valid requests are currently in the window.
local currentCount = redis.call('ZCARD', key)

-- ── Step 3 ──────────────────────────────────────────────────────────────────
-- Decision: allow if we are strictly under the cap.
if currentCount < maxRequests then

    -- Record this request with its timestamp as the score.
    -- The unique member prevents same-millisecond requests from colliding.
    redis.call('ZADD', key, now, member)

    -- Refresh the TTL so idle keys don't linger forever.
    -- 2× the window ensures the last request in a burst is always evicted cleanly.
    redis.call('EXPIRE', key, ttl)

    -- Return remaining capacity after recording this request.
    return maxRequests - currentCount - 1

else
    -- Window is full. Do NOT record anything — rejected requests
    -- must not consume capacity.
    return -1

end
