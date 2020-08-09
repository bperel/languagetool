/* LanguageTool, a natural language style checker
 * Copyright (C) 2018 Daniel Naber (http://www.danielnaber.de)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package org.languagetool.server;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.jdbc.SQL;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Encapsulate database access. Will do nothing if database access is not configured.
 * @since 4.2
 */
class DatabaseAccess {

  private static DatabaseAccess instance;
  private static SqlSessionFactory sqlSessionFactory;
  private static final Logger logger = LoggerFactory.getLogger(DatabaseAccess.class);

  private final Cache<Long, List<UserDictEntry>> userDictCache = CacheBuilder.newBuilder()
          .maximumSize(1000)
          .expireAfterWrite(24, TimeUnit.HOURS)
          .build();

  private final Cache<String, Long> dbLoggingCache = CacheBuilder.newBuilder()
    .expireAfterAccess(1, TimeUnit.HOURS)
    .maximumSize(5000)
    .build();

  private DatabaseAccess(HTTPServerConfig config) {
    if (config.getDatabaseDriver() != null) {
      try {
        logger.info("Setting up database access, URL " + config.getDatabaseUrl() + ", driver: " + config.getDatabaseDriver() + ", user: " + config.getDatabaseUsername());
        InputStream inputStream = Resources.getResourceAsStream("org/languagetool/server/mybatis-config.xml");
        Properties properties = new Properties();
        properties.setProperty("driver", config.getDatabaseDriver());
        properties.setProperty("url", config.getDatabaseUrl());
        properties.setProperty("username", config.getDatabaseUsername());
        properties.setProperty("password", config.getDatabasePassword());
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(inputStream, properties);

        // try to close connections even on hard restart
        // workaround as described in https://github.com/mybatis/mybatis-3/issues/821
        Runtime.getRuntime().addShutdownHook(new Thread(() -> ((PooledDataSource)sqlSessionFactory
          .getConfiguration().getEnvironment().getDataSource()).forceCloseAll()));

        DatabaseLogger.init(sqlSessionFactory);
        if (!config.getDatabaseLogging()) {
          logger.info("dbLogging not set to true, turning off logging");
          DatabaseLogger.getInstance().disableLogging();
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    } else {
      logger.info("Not setting up database access, dbDriver is not configured");
    }
  }
  
  static synchronized void init(HTTPServerConfig config) {
    if (instance == null) {
      instance = new DatabaseAccess(config);
    }
  }

  static synchronized DatabaseAccess getInstance() {
    if (instance == null) {
      throw new IllegalStateException("DatabaseAccess.init() has not been called yet");
    }
    return instance;
  }

  List<String> getUserDictWords(Long userId) {
    List<String> dictEntries = new ArrayList<>();
    if (sqlSessionFactory == null) {
      return dictEntries;
    }
    try (SqlSession session = sqlSessionFactory.openSession()) {
      try {
        List<UserDictEntry> dict = session.selectList("org.languagetool.server.UserDictMapper.selectWordList", userId);
        for (UserDictEntry userDictEntry : dict) {
          dictEntries.add(userDictEntry.getWord());
        }
        if (dict.size() <= 1000) {  // make sure users with huge dict don't blow up the cache
          userDictCache.put(userId, dict);
        } else {
          logger.info("WARN: Large dict size " + dict.size() + " for user " + userId + " - will not put user's dict in cache");
        }
      } catch (Exception e) {
        // try to be more robust when database is down, i.e. don't just crash but try to use cache:
        List<UserDictEntry> cachedDictOrNull = userDictCache.getIfPresent(userId);
        if (cachedDictOrNull != null) {
          logger.error("ERROR: Could not get words from database for user " + userId + ": " + e.getMessage() + ", will use cached version (" + cachedDictOrNull.size() + " items). Full stack trace follows:" + ExceptionUtils.getStackTrace(e));
          for (UserDictEntry userDictEntry : cachedDictOrNull) {
            dictEntries.add(userDictEntry.getWord());
          }
        } else {
          logger.error("ERROR: Could not get words from database for user " + userId + ": " + e.getMessage() + " - also, could not use version from cache, user id not found in cache, will use empty dict. Full stack trace follows:" + ExceptionUtils.getStackTrace(e));
        }
      }
    }
    return dictEntries;
  }

  List<UserDictEntry> getWords(Long userId, int offset, int limit) {
    if (sqlSessionFactory == null) {
      return new ArrayList<>();
    }
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      Map<Object, Object> map = new HashMap<>();
      map.put("userId", userId);
      return session.selectList("org.languagetool.server.UserDictMapper.selectWordList", map, new RowBounds(offset, limit));
    }
  }

  List<CorpusMatchEntry> getCorpusMatches(Map<String, String> usernames, int limit) {
    if (sqlSessionFactory == null) {
      return new ArrayList<>();
    }
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      Map<Object, Object> parameters = new HashMap<>();
      parameters.put("languageCodes", usernames.keySet());
      parameters.put("usernames", usernames);
      return session.selectList("org.languagetool.server.WikipediaMapper.selectNonAppliedWikipediaSuggestions", parameters, new RowBounds(0, limit));
    }
  }

  List<SkippedRule> getMostSkippedRules(Map<String, String> usernames) {
    if (sqlSessionFactory == null) {
      return new ArrayList<>();
    }
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      Map<Object, Object> parameters = new HashMap<>();
      parameters.put("languageCodes", usernames.keySet());
      parameters.put("usernames", usernames);
      List<HashMap<String, Object>> results = session.selectList("org.languagetool.server.WikipediaMapper.selectMostSkippedRules", parameters);
      return results.stream().map(result -> new SkippedRule(
        (String)result.get("language_code"),
        (String)result.get("ruleid"),
        (Long) result.get("skips_per_rule"),
        result.get("ignored").equals(1)
      )).collect(Collectors.toList());
    }
  }

  List<CorpusMatchEntry> getPastDecisions(HashMap<String, String> usernames, int limit) {
    if (sqlSessionFactory == null) {
      return new ArrayList<>();
    }
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      Map<Object, Object> parameters = new HashMap<>();
      parameters.put("languageCodes", usernames.keySet());
      parameters.put("usernames", usernames);
      return session.selectList("org.languagetool.server.WikipediaMapper.selectAppliedWikipediaSuggestions", parameters, new RowBounds(0, limit));
    }
  }

  CorpusArticleEntry getCorpusArticle(Integer articleId) {
    if (sqlSessionFactory == null) {
      return null;
    }
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      Map<Object, Object> map = new HashMap<>();
      map.put("id", articleId);
      return session.selectOne("org.languagetool.server.WikipediaMapper.selectWikipediaArticle", map);
    }
  }

  AccessToken getAccessToken(String languageCode, String accessToken) {
    if (sqlSessionFactory == null) {
      return null;
    }
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      Map<Object, Object> map = new HashMap<>();
      map.put("languageCode", languageCode);
      map.put("accessToken", accessToken);
      return session.selectOne("org.languagetool.server.WikipediaMapper.selectAccessToken", map);
    }
  }

  void createAccessToken(String languageCode, String username, String accessToken, String accessTokenSecret) {
    if (sqlSessionFactory == null) {
      return;
    }
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      Map<Object, Object> map = new HashMap<>();
      map.put("languageCode", languageCode);
      map.put("username", username);
      map.put("accessToken", accessToken);
      map.put("accessTokenSecret", accessTokenSecret);
      session.insert("org.languagetool.server.WikipediaMapper.insertAccessToken", map);
    }
  }

  public void removeAccessToken(String accessToken) {
    if (sqlSessionFactory == null) {
      return;
    }
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      Map<Object, Object> map = new HashMap<>();
      map.put("accessToken", accessToken);
      session.delete("org.languagetool.server.WikipediaMapper.deleteAccessToken", map);
    }
  }

  CorpusMatchEntry getCorpusMatch(int suggestionId) {
    if (sqlSessionFactory == null) {
      return null;
    }
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      Map<Object, Object> map = new HashMap<>();
      map.put("id", suggestionId);
      return session.selectOne("org.languagetool.server.WikipediaMapper.selectWikipediaSuggestion", map);
    }
  }

  boolean resolveCorpusMatch(int suggestionId, String username, boolean shouldBecomeApplied, String reason) {
    if (sqlSessionFactory == null) {
      return false;
    }
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      Map<Object, Object> map = new HashMap<>();
      map.put("id", suggestionId);
      map.put("username", username);
      map.put("applied", shouldBecomeApplied);
      map.put("reason", reason);
      int affectedRows = session.update("org.languagetool.server.WikipediaMapper.updateWikipediaSuggestion", map);
      return affectedRows >= 1;
    }
  }

  boolean skipCorpusMatch(int suggestionId, String username) {
    if (sqlSessionFactory == null) {
      return false;
    }
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      Map<Object, Object> map = new HashMap<>();
      map.put("corpus_match_id", suggestionId);
      map.put("username", username);
      int affectedRows = session.update("org.languagetool.server.WikipediaMapper.skipWikipediaSuggestion", map);
      return affectedRows >= 1;
    }
  }


  public boolean toggleIgnoredRule(String languageCode, String ruleId, String username, Boolean toggle) {
    if (sqlSessionFactory == null) {
      return false;
    }
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      Map<Object, Object> map = new HashMap<>();
      map.put("languageCode", languageCode);
      map.put("ruleid", ruleId);
      map.put("username", username);
      int affectedRows;
      if (toggle) {
        affectedRows = session.insert("org.languagetool.server.WikipediaMapper.addIgnoredRule", map);
      }
      else {
        affectedRows = session.delete("org.languagetool.server.WikipediaMapper.removeIgnoredRule", map);
      }
      return affectedRows == 1;
    }
  }

  List<DayStatistics> getDecisionStats() {
    if (sqlSessionFactory == null) {
      return null;
    }
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      return session.selectList("org.languagetool.server.WikipediaMapper.getDecisionStats");
    }
  }

  List<WeekStatistics> getMonthlyDecisionPercentage() {
    if (sqlSessionFactory == null) {
      return null;
    }
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      return session.selectList("org.languagetool.server.WikipediaMapper.getDecisionStatsPercentageWeek");
    }
  }

  List<ContributionStatisticsPerMonth> getContributorsStats() {
    final int limitPerMonthAndLanguage = 3;
    if (sqlSessionFactory == null) {
      return null;
    }
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      List<ContributionStatisticsPerMonth> topContributors = session.selectList("org.languagetool.server.WikipediaMapper.getTopContributors");
      List<ContributionStatisticsPerMonth> topContributorsLimited = new ArrayList<>();

      String previousMonthAndLanguage = "";
      int currentLimit = 0;
      for (ContributionStatisticsPerMonth contributionForLanguageAndUser : topContributors) {
        String monthAndLanguage = String.format("%s-%s",
          contributionForLanguageAndUser.date,
          contributionForLanguageAndUser.languageCode);
        if (! previousMonthAndLanguage.equals(monthAndLanguage) || currentLimit < limitPerMonthAndLanguage) {
          topContributorsLimited.add(contributionForLanguageAndUser);
          if (! previousMonthAndLanguage.equals(monthAndLanguage)) {
            currentLimit = 0;
          }
          previousMonthAndLanguage = monthAndLanguage;
          currentLimit++;
        }
      }
      return topContributorsLimited;
    }
  }

  List<PendingSuggestionsPerLanguageCode> getPendingSuggestionsStats() {
    if (sqlSessionFactory == null) {
      return null;
    }
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      return session.selectList("org.languagetool.server.WikipediaMapper.getPendingSuggestionsPerLanguageCode");
    }
  }

  List<RefusedSuggestionCategoryPerLanguageCode> getMostRefusedSuggestionCategoriesPerLanguageCode() {
    if (sqlSessionFactory == null) {
      return null;
    }
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      return session.selectList("org.languagetool.server.WikipediaMapper.getMostRefusedSuggestionCategoriesPerLanguageCode");
    }
  }
  
  boolean addWord(String word, Long userId) {
    validateWord(word);
    if (sqlSessionFactory == null) {
      return false;
    }
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      Map<Object, Object> map = new HashMap<>();
      map.put("word", word);
      map.put("userId", userId);
      List<UserDictEntry> existingWords = session.selectList("org.languagetool.server.UserDictMapper.selectWord", map);
      if (existingWords.size() >= 1) {
        logger.info("Did not add '" + word + "' for user " + userId + " to list of ignored words, already exists");
        return false;
      } else {
        Date now = new Date();
        map.put("created_at", now);
        map.put("updated_at", now);
        int affectedRows = session.insert("org.languagetool.server.UserDictMapper.addWord", map);
        logger.info("Added '" + word + "' for user " + userId + " to list of ignored words, affectedRows: " + affectedRows);
        return affectedRows == 1;
      }
    }
  }

  Long getUserId(String username, String apiKey) {
    if (username == null || username.trim().isEmpty()) {
      throw new IllegalArgumentException("username must be set");
    }
    if (apiKey == null || apiKey.trim().isEmpty()) {
      throw new IllegalArgumentException("apiKey must be set");
    }
    if (sqlSessionFactory ==  null) {
      throw new IllegalStateException("sqlSessionFactory not initialized - has the database been configured?");
    }
    try {
      Long value = dbLoggingCache.get(String.format("user_%s_%s", username, apiKey), () -> {
        try (SqlSession session = sqlSessionFactory.openSession()) {
          Map<Object, Object> map = new HashMap<>();
          map.put("username", username);
          map.put("apiKey", apiKey);
          Long id = session.selectOne("org.languagetool.server.UserDictMapper.getUserIdByApiKey", map);
          if (id == null) {
            return -1L;
          }
          return id;
        }
      });
      if (value == -1) {
        throw new IllegalArgumentException("No user found for given username '" + username + "' and given api key");
      } else {
        return value;
      }
    } catch (ExecutionException e) {
      throw new IllegalStateException("Could not fetch given user '" + username + "' from cache", e);
    }
  }

  boolean deleteWord(String word, Long userId) {
    if (sqlSessionFactory == null) {
      return false;
    }
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      Map<Object, Object> map = new HashMap<>();
      map.put("word", word);
      map.put("userId", userId);
      int count = session.delete("org.languagetool.server.UserDictMapper.selectWord", map);
      if (count == 0) {
        logger.info("Did not delete '" + word + "' for user " + userId + " from list of ignored words, does not exist");
        return false;
      } else {
        int affectedRows = session.delete("org.languagetool.server.UserDictMapper.deleteWord", map);
        logger.info("Deleted '" + word + "' for user " + userId + " from list of ignored words, affectedRows: " + affectedRows);
        return affectedRows >= 1;
      }
    }
  }

  /**
   * @since 4.3
   */
  Long getOrCreateServerId() {
    if (sqlSessionFactory == null) {
      return null;
    }
    try {
      String hostname = InetAddress.getLocalHost().getHostName();
      Long id = dbLoggingCache.get("server_" + hostname, () -> {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
          Map<Object, Object> parameters = new HashMap<>();
          parameters.put("hostname", hostname);
          List<Long> result = session.selectList("org.languagetool.server.LogMapper.findServer", parameters);
          if (result.size() > 0) {
            return result.get(0);
          } else {
            session.insert("org.languagetool.server.LogMapper.newServer", parameters);
            Object value = parameters.get("id");
            if (value == null) {
              //System.err.println("Could not get new server id for this host.");
              return -1L;
            } else {
              return (Long) value;
            }
          }
        } catch (PersistenceException e) {
          logger.warn("Error: Could not fetch/register server id from database for server: " + hostname, e);
          return -1L;
        }
      });
      if (id == -1L) { // loaders can't return null, so using -1 instead
        return null;
      } else {
        return id;
      }
    } catch (UnknownHostException | ExecutionException e) {
      logger.warn("Error: Could not get hostname to fetch/register server id: ", e);
      return null;
    }
  }

  /**
   * @since 4.3
   */
  Long getOrCreateClientId(String client) {
    if (sqlSessionFactory == null || client == null) {
      return null;
    }
    try {
      Long id = dbLoggingCache.get("client_" + client, () -> {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
          Map<Object, Object> parameters = new HashMap<>();
          parameters.put("name", client);
          List<Long> result = session.selectList("org.languagetool.server.LogMapper.findClient", parameters);
          if (result.size() > 0) {
            return result.get(0);
          } else {
            session.insert("org.languagetool.server.LogMapper.newClient", parameters);
            Object value = parameters.get("id");
            if (value == null) {
              //System.err.println("Could not get/register id for this client.");
              return -1L;
            } else {
              return (Long) value;
            }
          }
        } catch (PersistenceException e) {
          logger.warn("Error: Could not get/register id for this client: " + client, e);
          return -1L;
        }
      });
      if (id == -1L) { // loaders can't return null, so using -1 instead
        return null;
      } else {
        return id;
      }
    } catch (ExecutionException e) {
      logger.warn("Failure in getOrCreateClientId with client '" + client + "': ", e);
      return null;
    }
  }
  
  private void validateWord(String word) {
    if (word == null || word.trim().isEmpty()) {
      throw new IllegalArgumentException("Invalid word, cannot be empty or whitespace only");
    }
    if (word.matches(".*\\s.*")) {
      throw new IllegalArgumentException("Invalid word, you can only words that don't contain spaces: '" + word + "'");
    }
  }

  /** For unit tests only! */
  public static void createAndFillTestTables() {
    createAndFillTestTables(false);
  }

  /** For unit tests only! */
  public static void createAndFillTestTables(boolean mysql) {
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      System.out.println("Setting up tables and adding test user...");
      String[] statements = { "org.languagetool.server.UserDictMapper.createUserTable",
        "org.languagetool.server.UserDictMapper.createIgnoreWordTable" };
      for (String statement : statements) {
        if (mysql) {
          session.insert(statement + "MySQL");
        } else {
          session.insert(statement);
        }
      }
      session.insert("org.languagetool.server.UserDictMapper.createTestUser1");
      session.insert("org.languagetool.server.UserDictMapper.createTestUser2");
    }
  }
  
  /** For unit tests only! */
  public static void deleteTestTables() {
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      System.out.println("Deleting tables...");
      session.delete("org.languagetool.server.UserDictMapper.deleteUsersTable");
      session.delete("org.languagetool.server.UserDictMapper.deleteIgnoreWordsTable");
    }
  }

  /** For unit tests only */
  static ResultSet executeStatement(SQL sql) throws SQLException {
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      try (Connection conn = session.getConnection()) {
        try (Statement stmt = conn.createStatement()) {
          return stmt.executeQuery(sql.toString());
        }
      }
    }
  }

  public static class DayStatistics {
    private final String date;
    private final Boolean applied;
    private final Integer count;

    public DayStatistics(String date, Boolean applied, Integer count) {
      this.date = date;
      this.applied = applied;
      this.count = count;
    }

    public String getDate() {
      return date;
    }

    public Boolean getApplied() {
      return applied;
    }

    public Integer getCount() {
      return count;
    }
  }

  public static class WeekStatistics {
    private final String month;
    private final Float appliedPercentage;

    public WeekStatistics(String month, Float appliedPercentage) {
      this.month = month;
      this.appliedPercentage = appliedPercentage;
    }

    public String getMonth() {
      return month;
    }

    public Float getAppliedPercentage() {
      return appliedPercentage;
    }
  }

  public static class ContributionStatisticsPerMonth {
    private final String date;
    private final String languageCode;
    private final String username;
    private final Integer count;

    public ContributionStatisticsPerMonth(String date, String languageCode, String username, Integer count) {
      this.date = date;
      this.languageCode = languageCode;
      this.username = username;
      this.count = count;
    }

    public String getDate() {
      return date;
    }

    public String getLanguageCode() {
      return languageCode;
    }

    public String getUsername() {
      return username;
    }

    public Integer getCount() {
      return count;
    }
  }

  public static class PendingSuggestionsPerLanguageCode {
    private final String languageCode;
    private final Integer count;

    public PendingSuggestionsPerLanguageCode(String languageCode, Integer count) {
      this.languageCode = languageCode;
      this.count = count;
    }

    public String getLanguageCode() {
      return languageCode;
    }

    public Integer getCount() {
      return count;
    }
  }

  public static class RefusedSuggestionCategoryPerLanguageCode {
    private final String languagetoolVersion;
    private final String languageCode;
    private final String ruleCategory;
    private final String ruleDescription;
    private final Integer count;
    private final Integer sampleSuggestionId;

    public RefusedSuggestionCategoryPerLanguageCode(String languagetoolVersion, String languageCode, String ruleCategory, String ruleDescription, Integer count, Integer sampleSuggestionId) {
      this.languagetoolVersion = languagetoolVersion;
      this.languageCode = languageCode;
      this.ruleCategory = ruleCategory;
      this.ruleDescription = ruleDescription;
      this.count = count;
      this.sampleSuggestionId = sampleSuggestionId;
    }

    public String getLanguagetoolVersion() {
      return languagetoolVersion;
    }

    public String getLanguageCode() {
      return languageCode;
    }

    public String getRuleCategory() {
      return ruleCategory;
    }

    public String getRuleDescription() {
      return ruleDescription;
    }

    public Integer getCount() {
      return count;
    }

    public Integer getSampleSuggestionId() {
      return sampleSuggestionId;
    }
  }

  public static class OriginalAndSuggestedTexts {
    private final String originalWikitext;
    private final String suggestedWikitext;
    private final String originalHtml;

    public OriginalAndSuggestedTexts(String originalWikitext, String suggestedWikitext, String originalHtml) {
      this.originalWikitext = originalWikitext;
      this.suggestedWikitext = suggestedWikitext;
      this.originalHtml = originalHtml;
    }

    public String getOriginalWikitext() {
      return originalWikitext;
    }

    public String getSuggestedWikitext() {
      return suggestedWikitext;
    }

    public String getOriginalHtml() {
      return originalHtml;
    }
  }

  public static class SkippedRule {
    private final String languageCode;
    private final String ruleId;
    private final Long timesSkipped;
    private final Boolean isIgnored;

    public SkippedRule(String languageCode, String ruleId, Long timesSkipped, Boolean isIgnored) {
      this.languageCode = languageCode;
      this.ruleId = ruleId;
      this.timesSkipped = timesSkipped;
      this.isIgnored = isIgnored;
    }

    public String getLanguageCode() {
      return languageCode;
    }

    public String getRuleId() {
      return ruleId;
    }

    public Long getTimesSkipped() {
      return timesSkipped;
    }

    public Boolean getIgnored() {
      return isIgnored;
    }
  }
}
