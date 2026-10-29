package model.router.service;

import java.time.LocalDate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 计量台账：按 key + 自然日累计字符数与请求数，既做配额拦截，也是后续出账的基础。
 */
@Repository
public class UsageLedger {

  public record UsageTotals(long requests, long chars, long cached) {}

  private final JdbcTemplate jdbcTemplate;

  public UsageLedger(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public long charsUsedToday(String apiKey) {
    Long value = jdbcTemplate.queryForObject(
        "SELECT COALESCE(SUM(char_count), 0) FROM usage_daily WHERE api_key = ? AND usage_day = ?",
        Long.class, apiKey, LocalDate.now());
    return value == null ? 0L : value;
  }

  /** 全部 key 当日合计，供状态页展示，不区分到具体 key。 */
  public UsageTotals totalsToday() {
    return jdbcTemplate.queryForObject(
        "SELECT COALESCE(SUM(request_count), 0), COALESCE(SUM(char_count), 0), COALESCE(SUM(cached_count), 0)"
            + " FROM usage_daily WHERE usage_day = ?",
        (rs, rowNum) -> new UsageTotals(rs.getLong(1), rs.getLong(2), rs.getLong(3)),
        LocalDate.now());
  }

  public void record(String apiKey, long chars, boolean cached) {
    LocalDate today = LocalDate.now();
    if (update(apiKey, today, chars, cached) > 0) {
      return;
    }
    try {
      jdbcTemplate.update(
          "INSERT INTO usage_daily (api_key, usage_day, char_count, request_count, cached_count)"
              + " VALUES (?, ?, ?, ?, ?)",
          apiKey, today, chars, 1, cached ? 1 : 0);
    } catch (DuplicateKeyException raced) {
      update(apiKey, today, chars, cached);
    }
  }

  private int update(String apiKey, LocalDate day, long chars, boolean cached) {
    return jdbcTemplate.update(
        "UPDATE usage_daily SET char_count = char_count + ?, request_count = request_count + 1,"
            + " cached_count = cached_count + ? WHERE api_key = ? AND usage_day = ?",
        chars, cached ? 1 : 0, apiKey, day);
  }
}
