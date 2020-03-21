/* LanguageTool, a natural language style checker 
 * Copyright (C) 2013 Daniel Naber (http://www.danielnaber.de)
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
package org.languagetool.dev.dumpcheck;

import org.apache.commons.lang3.StringUtils;
import org.languagetool.Language;
import org.languagetool.rules.Rule;
import org.languagetool.rules.RuleMatch;
import org.languagetool.rules.patterns.AbstractPatternRule;
import org.languagetool.tools.ContextTools;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.*;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Store rule matches to a database.
 * @since 2.4
 */
class CorpusMatchDatabaseHandler extends ResultHandler {

  protected static Connection conn;
  protected static int batchSize;
  protected static int batchCount = 0;

  static final int MAX_CONTEXT_LENGTH = 500;
  private static final int SMALL_CONTEXT_LENGTH = 40;  // do not modify - it would break lookup of errors marked as 'false alarm'

  static final ContextTools contextTools;
  private static final ContextTools smallContextTools;

  private PreparedStatement selectCorpusArticleWikitextFromId;
  private PreparedStatement selectCorpusArticleWithRevisionSt;
  private PreparedStatement insertCorpusArticleSt;
  private PreparedStatement insertCorpusMatchSt;
  private PreparedStatement deleteNeverAppliedSuggestionsOfObsoleteArticles;

  static {
    contextTools = new ContextTools();
    contextTools.setContextSize(MAX_CONTEXT_LENGTH);
    contextTools.setErrorMarkerStart(MARKER_START);
    contextTools.setErrorMarkerEnd(MARKER_END);
    contextTools.setEscapeHtml(false);
    smallContextTools = new ContextTools();
    smallContextTools.setContextSize(SMALL_CONTEXT_LENGTH);
    smallContextTools.setErrorMarkerStart(MARKER_START);
    smallContextTools.setErrorMarkerEnd(MARKER_END);
    smallContextTools.setEscapeHtml(false);
  }

  CorpusMatchDatabaseHandler(File propertiesFile, int maxSentences, int maxErrors) {
    super(maxSentences, maxErrors);

    if (conn == null) {
      Properties dbProperties = new Properties();
      try (FileInputStream inStream = new FileInputStream(propertiesFile)) {
        dbProperties.load(inStream);
        String dbUrl = getProperty(dbProperties, "dbUrl");
        String dbUser = getProperty(dbProperties, "dbUsername");
        String dbPassword = getProperty(dbProperties, "dbPassword");
        batchSize = Integer.decode(dbProperties.getProperty("batchSize", "1"));
        conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
      } catch (SQLException | IOException e) {
        throw new RuntimeException(e);
      }
      try {
        selectCorpusArticleWikitextFromId = conn.prepareStatement("" +
          " SELECT wikitext FROM corpus_article" +
          " WHERE id = ?");
        selectCorpusArticleWithRevisionSt = conn.prepareStatement("" +
          " SELECT id FROM corpus_article" +
          " WHERE title = ? AND revision = ?");
        insertCorpusArticleSt = conn.prepareStatement("" +
          " INSERT INTO corpus_article (title, revision, wikitext, anonymized_html)" +
          " VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
        insertCorpusMatchSt = conn.prepareStatement("" +
          " INSERT INTO corpus_match (article_id, version, language_code, ruleid, rule_category, rule_subid, rule_description, message, error_context, small_error_context, corpus_date, check_date, source_type, replacement_suggestion, is_visible)" +
          " VALUES (?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)");
        deleteNeverAppliedSuggestionsOfObsoleteArticles = conn.prepareStatement("" +
          " DELETE FROM corpus_match" +
          " WHERE applied IS NULL AND article_id IN (SELECT id FROM corpus_article WHERE title = ? AND revision <> ? )");
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
    }
  }

  private String getProperty(Properties prop, String key) {
    String value = prop.getProperty(key);
    if (value == null) {
      throw new RuntimeException("Required key '" + key + "' not found in properties");
    }
    return value;
  }

  @Override
  protected void handleResult(Sentence sentence, List<RuleMatch> ruleMatches, Language language) {
    try {
      java.sql.Date nowDate = new java.sql.Date(new java.util.Date().getTime());
      List<RuleMatch> rulesMatchesWithSuggestions = ruleMatches.stream()
        .filter(match -> !match.getSuggestedReplacements().isEmpty())
        .collect(Collectors.toList());

      for (RuleMatch match : rulesMatchesWithSuggestions) {
        String context = contextTools.getContext(match.getFromPos(), match.getToPos(), sentence.getText());
        String smallContext = smallContextTools.getContext(match.getFromPos(), match.getToPos(), sentence.getText());

        addSentenceToBatch(sentence.getArticleId(), sentence.getSource(), language, nowDate, match, context, smallContext);
        if (++batchCount >= batchSize) {
          executeBatch();
          batchCount = 0;
        }

        checkMaxErrors(++errorCount);
        if (errorCount % 100 == 0) {
          System.out.println("Storing error #" + errorCount + " for text:");
          System.out.println("  " + sentence.getText());
        }
      }
      checkMaxSentences(++sentenceCount);
    } catch (DocumentLimitReachedException | ErrorLimitReachedException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("Error storing matches for '" + sentence.getTitle() + "'", e);
    }
  }

  void deleteNeverAppliedSuggestionsOfObsoleteArticles(String title, int revision) throws SQLException {
    deleteNeverAppliedSuggestionsOfObsoleteArticles.setString(1, title);
    deleteNeverAppliedSuggestionsOfObsoleteArticles.setInt(2, revision);
    deleteNeverAppliedSuggestionsOfObsoleteArticles.execute();
  }

  Long createArticle(String title, int revision, String wikitext, String anonymizedHtml) throws SQLException {
    insertCorpusArticleSt.setString(1, title);
    insertCorpusArticleSt.setInt(2, revision);
    insertCorpusArticleSt.setString(3, wikitext);
    insertCorpusArticleSt.setString(4, anonymizedHtml);
    insertCorpusArticleSt.execute();
    ResultSet generatedKeys = insertCorpusArticleSt.getGeneratedKeys();
    if (generatedKeys.next()) {
      return generatedKeys.getLong(1);
    }

    throw new SQLException("Couldn't create article " + title);
  }

  private void addSentenceToBatch(long articleId, String source, Language language, Date nowDate, RuleMatch match, String context, String smallContext) throws SQLException {

    Rule rule = match.getRule();
    insertCorpusMatchSt.setLong(1, articleId);
    insertCorpusMatchSt.setString(2, language.getShortCode());
    insertCorpusMatchSt.setString(3, rule.getId());
    insertCorpusMatchSt.setString(4, rule.getCategory().getName());
    if (rule instanceof AbstractPatternRule) {
      insertCorpusMatchSt.setString(5, ((AbstractPatternRule) rule).getSubId());
    } else {
      insertCorpusMatchSt.setNull(5, Types.VARCHAR);
    }
    insertCorpusMatchSt.setString(6, rule.getDescription());
    insertCorpusMatchSt.setString(7, StringUtils.abbreviate(match.getMessage(), 255));
    insertCorpusMatchSt.setString(8, context);
    insertCorpusMatchSt.setString(9, StringUtils.abbreviate(smallContext, 255));

    insertCorpusMatchSt.setDate(10, nowDate);  // should actually be the dump's date, but isn't really used anyway...
    insertCorpusMatchSt.setDate(11, nowDate);
    insertCorpusMatchSt.setString(12, source);
    insertCorpusMatchSt.setString(13, match.getSuggestedReplacements().get(0));
    insertCorpusMatchSt.addBatch();
  }

  void executeBatch() throws SQLException {
    boolean autoCommit = conn.getAutoCommit();
    conn.setAutoCommit(false);
    try {
      insertCorpusMatchSt.executeBatch();
      if (autoCommit) {
        conn.commit();
      }
    } finally {
      conn.setAutoCommit(autoCommit);
    }
  }

  String getCorpusArticleWikitextFromId(Long articleId) throws SQLException {
    selectCorpusArticleWikitextFromId.setLong(1, articleId);
    ResultSet corpusArticleResultSet = selectCorpusArticleWikitextFromId.executeQuery();
    if (corpusArticleResultSet.next()) {
      return corpusArticleResultSet.getString(1);
    }
    throw new SQLException("No such article : " + articleId);
  }

  Long getArticleIdFromDb(String title, int revision) throws SQLException {
    selectCorpusArticleWithRevisionSt.setString(1, title);
    selectCorpusArticleWithRevisionSt.setInt(2, revision);

    ResultSet corpusArticleResultSet = selectCorpusArticleWithRevisionSt.executeQuery();
    if (corpusArticleResultSet.next()) {
      return corpusArticleResultSet.getLong(1);
    } else {
      return null;
    }
  }

  @Override
  public void close() throws Exception {
    for (PreparedStatement preparedStatement : Arrays.asList(insertCorpusArticleSt, insertCorpusMatchSt)) {
      if (preparedStatement != null) {
        if (batchCount > 0) {
          executeBatch();
        }
        preparedStatement.close();
      }
    }
    if (conn != null) {
      conn.close();
    }
  }
}
