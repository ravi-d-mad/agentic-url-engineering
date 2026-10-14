CREATE TABLE click_daily (
  short_url_id BIGINT NOT NULL REFERENCES short_urls(id),
  day DATE NOT NULL,
  clicks BIGINT NOT NULL,
  PRIMARY KEY (short_url_id, day)
);
