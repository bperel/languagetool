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
import org.languagetool.JLanguageTool;
import org.languagetool.dev.dumpcheck.SentenceSourceChecker.RuleMatchWithHtmlContexts;
import org.languagetool.rules.Rule;
import org.languagetool.rules.patterns.AbstractPatternRule;
import org.languagetool.tools.ContextTools;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.*;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Store rule matches to a database.
 * @since 2.4
 */
class CorpusMatchDatabaseHandler implements AutoCloseable {

  private static final String MARKER_START = "<err>";
  private static final String MARKER_END = "</err>";

  private int sentenceCount = 0;
  private int errorCount = 0;

  private final int maxSentences;
  private final int maxErrors;

  private final Connection conn;

  static final int MAX_CONTEXT_LENGTH = 500;
  private static final int SMALL_CONTEXT_LENGTH = 40;  // do not modify - it would break lookup of errors marked as 'false alarm'

  static final ContextTools contextTools;
  static final ContextTools smallContextTools;

  private final PreparedStatement selectCorpusArticleWithEqualOrHigherRevisionSt;
  private final PreparedStatement insertCorpusArticleSt;
  private final PreparedStatement insertCorpusMatchSt;
  private final PreparedStatement deleteNeverAppliedSuggestionsOfObsoleteArticles;
  private final PreparedStatement deleteAlreadyAppliedSuggestionsInNewArticleRevisions;
  private final PreparedStatement updateCorpusArticleMarkAsAnalyzed;

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
    this.maxSentences = maxSentences;
    this.maxErrors = maxErrors;

    Properties dbProperties = new Properties();
    try (FileInputStream inStream = new FileInputStream(propertiesFile)) {
      dbProperties.load(inStream);
      String dbUrl = getProperty(dbProperties, "dbUrl");
      String dbUser = getProperty(dbProperties, "dbUsername");
      String dbPassword = getProperty(dbProperties, "dbPassword");
      conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
    } catch (SQLException | IOException e) {
      throw new RuntimeException(e);
    }
    try {
      selectCorpusArticleWithEqualOrHigherRevisionSt = conn.prepareStatement("" +
        " SELECT id, revision, analyzed, wikitext, css_url, html, anonymized_html FROM corpus_article article" +
        " WHERE title = ? AND revision = (SELECT MAX(revision) from corpus_article where title=article.title) AND revision >= ?");
      insertCorpusArticleSt = conn.prepareStatement("" +
        " INSERT INTO corpus_article (language_code, title, revision, wikitext, html, anonymized_html, css_url, analyzed)" +
        " VALUES (?, ?, ?, ?, ?, ?, ?, 0)", Statement.RETURN_GENERATED_KEYS);
      insertCorpusMatchSt = conn.prepareStatement("" +
        " INSERT INTO corpus_match (article_id, ruleid, rule_category, rule_subid, rule_description, message, error_context, small_error_context, html_error_context, replacement_suggestion, languagetool_version)" +
        " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
      deleteNeverAppliedSuggestionsOfObsoleteArticles = conn.prepareStatement("" +
        " DELETE FROM corpus_match" +
        " WHERE applied IS NULL AND article_id IN (SELECT id FROM corpus_article WHERE title = ? AND language_code = ? AND revision <> ? )");
      deleteAlreadyAppliedSuggestionsInNewArticleRevisions = conn.prepareStatement("" +
        " DELETE from corpus_match WHERE article_id = ? AND id IN " +
        " (SELECT m.id" +
        "  FROM corpus_match m, corpus_match m2" +
        "  WHERE m.id > m2.id" +
        "    AND (select a.url from corpus_article a where a.id = m.article_id) =" +
        "        (select a2.url from corpus_article a2 where a2.id = m2.article_id)" +
        "    AND m.ruleid = m2.ruleid" +
        "    AND m.rule_subid = m2.rule_subid" +
        "    AND m.error_context = m2.error_context" +
        "    AND m.applied is null" +
        "    AND m2.applied is not null" +
        " )");
      updateCorpusArticleMarkAsAnalyzed = conn.prepareStatement("" +
        " UPDATE corpus_article" +
        " SET analyzed = 1 WHERE id = ?");
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  protected void checkMaxSentences() {
    if (maxSentences > 0 && sentenceCount >= maxSentences) {
      throw new DocumentLimitReachedException(maxSentences);
    }
  }

  protected void checkMaxErrors() {
    if (maxErrors > 0 && errorCount >= maxErrors) {
      throw new ErrorLimitReachedException(maxErrors);
    }
  }

  private String getProperty(Properties prop, String key) {
    String value = prop.getProperty(key);
    if (value == null) {
      throw new RuntimeException("Required key '" + key + "' not found in properties");
    }
    return value;
  }

  protected void handleResult(Sentence sentence, List<RuleMatchWithHtmlContexts> rulesMatchesWithSuggestions) throws SQLIntegrityConstraintViolationException {
    try {
      for (RuleMatchWithHtmlContexts match : rulesMatchesWithSuggestions) {
        createSentence(sentence.getArticleId(), match);
        ++errorCount;
        checkMaxErrors();
      }
      ++sentenceCount;
      checkMaxSentences();
    }
    catch(SQLIntegrityConstraintViolationException | DocumentLimitReachedException | ErrorLimitReachedException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("Error storing matches for '" + sentence.getTitle() + "'", e);
    }
  }

  void deleteNeverAppliedSuggestionsOfObsoleteArticles(String title, String languageCode, int revision) throws SQLException {
    deleteNeverAppliedSuggestionsOfObsoleteArticles.setString(1, title);
    deleteNeverAppliedSuggestionsOfObsoleteArticles.setString(2, languageCode);
    deleteNeverAppliedSuggestionsOfObsoleteArticles.setInt(3, revision);
    System.out.println("deleteNeverAppliedSuggestionsOfObsoleteArticles : deleted rows = "
      + deleteNeverAppliedSuggestionsOfObsoleteArticles.executeUpdate()
    );
  }

  void deleteAlreadyAppliedSuggestionsInNewArticleRevisions(Long articleId) throws SQLException {
    deleteAlreadyAppliedSuggestionsInNewArticleRevisions.setLong(1, articleId);
    System.out.println("deleteAlreadyAppliedSuggestionsInNewArticleRevisions : deleted rows = "
      + deleteAlreadyAppliedSuggestionsInNewArticleRevisions.executeUpdate()
    );
  }

  Long createArticle(String languageCode, String title, int revision, String wikitext, String html, String anonymizedHtml, String cssUrl) throws SQLException {
    insertCorpusArticleSt.setString(1, languageCode);
    insertCorpusArticleSt.setString(2, title);
    insertCorpusArticleSt.setInt(3, revision);
    insertCorpusArticleSt.setString(4, wikitext);
    insertCorpusArticleSt.setString(5, html);
    insertCorpusArticleSt.setString(6, anonymizedHtml);
    insertCorpusArticleSt.setString(7, cssUrl);
    insertCorpusArticleSt.execute();
    ResultSet generatedKeys = insertCorpusArticleSt.getGeneratedKeys();
    if (generatedKeys.next()) {
      return generatedKeys.getLong(1);
    }

    throw new SQLException("Couldn't create article " + title);
  }

  private void createSentence(long articleId, RuleMatchWithHtmlContexts match) throws SQLException {

    Rule rule = match.getRule();
    insertCorpusMatchSt.setLong(1, articleId);
    insertCorpusMatchSt.setString(2, rule.getId());
    insertCorpusMatchSt.setString(3, rule.getCategory().getName());
    if (rule instanceof AbstractPatternRule) {
      insertCorpusMatchSt.setString(4, ((AbstractPatternRule) rule).getSubId());
    } else {
      insertCorpusMatchSt.setNull(4, Types.VARCHAR);
    }
    insertCorpusMatchSt.setString(5, rule.getDescription());
    insertCorpusMatchSt.setString(6, StringUtils.abbreviate(match.getMessage(), 255));
    insertCorpusMatchSt.setString(7, match.getTextContext());
    insertCorpusMatchSt.setString(8, StringUtils.abbreviate(match.getSmallTextContext(), 255));
    insertCorpusMatchSt.setString(9, match.getHtmlContext());

    insertCorpusMatchSt.setString(10, match.getSuggestedReplacements().get(0));
    insertCorpusMatchSt.setString(11, JLanguageTool.VERSION);
    insertCorpusMatchSt.executeQuery();
  }

  Object[] getAnalyzedArticle(String title, int revision) throws SQLException {
    selectCorpusArticleWithEqualOrHigherRevisionSt.setString(1, title);
    selectCorpusArticleWithEqualOrHigherRevisionSt.setInt(2, revision);

    ResultSet corpusArticleResultSet = selectCorpusArticleWithEqualOrHigherRevisionSt.executeQuery();
    if (corpusArticleResultSet.next()) {
      return new Object[]{
        corpusArticleResultSet.getLong(1),
        corpusArticleResultSet.getLong(2),
        corpusArticleResultSet.getBoolean(3),
        corpusArticleResultSet.getString(4),
        corpusArticleResultSet.getString(5),
        corpusArticleResultSet.getString(6),
        corpusArticleResultSet.getString(7)
      };
    } else {
      return null;
    }
  }

  public void markArticleAsAnalyzed(Long currentArticleId) throws SQLException {
    updateCorpusArticleMarkAsAnalyzed.setLong(1, currentArticleId);
    updateCorpusArticleMarkAsAnalyzed.execute();
  }

  @Override
  public void close() throws Exception {
    for (PreparedStatement preparedStatement : Arrays.asList(
      selectCorpusArticleWithEqualOrHigherRevisionSt,
      insertCorpusArticleSt,
      insertCorpusMatchSt,
      deleteNeverAppliedSuggestionsOfObsoleteArticles,
      deleteAlreadyAppliedSuggestionsInNewArticleRevisions,
      updateCorpusArticleMarkAsAnalyzed
    )) {
      if (preparedStatement != null) {
        preparedStatement.close();
      }
    }
    conn.close();
  }
}
