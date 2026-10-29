CREATE TABLE IF NOT EXISTS usage_daily (
  api_key VARCHAR(128) NOT NULL,
  usage_day DATE NOT NULL,
  char_count BIGINT NOT NULL DEFAULT 0,
  request_count INT NOT NULL DEFAULT 0,
  cached_count INT NOT NULL DEFAULT 0,
  PRIMARY KEY (api_key, usage_day)
);
