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
import org.languagetool.tools.HtmlTools;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.Date;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Store rule matches to a database.
 * @since 2.4
 */
abstract class DatabaseHandler extends ResultHandler {

  protected static Connection conn;
  protected static int batchSize;
  
  protected int batchCount = 0;

  DatabaseHandler(File propertiesFile, int maxSentences, int maxErrors) {
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
    }
  }

  private String getProperty(Properties prop, String key) {
    String value = prop.getProperty(key);
    if (value == null) {
      throw new RuntimeException("Required key '" + key + "' not found in properties");
    }
    return value;
  }

  static class CorpusMatchDatabaseHandler extends DatabaseHandler {
    private static final int MAX_CONTEXT_LENGTH = 500;
    private static final int SMALL_CONTEXT_LENGTH = 40;  // do not modify - it would break lookup of errors marked as 'false alarm'

    private final ContextTools contextTools;
    private final ContextTools smallContextTools;

    private final PreparedStatement insertCorpusArticleSt;
    private final PreparedStatement insertCorpusMatchSt;

    private Set<String> processedAnonymizedArticles = new HashSet<>();

    CorpusMatchDatabaseHandler(File propertiesFile, int maxSentences, int maxErrors) {
      super(propertiesFile, maxSentences, maxErrors);

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

      try {
        insertCorpusArticleSt = conn.prepareStatement("" +
          " INSERT INTO corpus_article (title, revision, wikitext, anonymized_html)" +
          " VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
        insertCorpusMatchSt = conn.prepareStatement("" +
          " INSERT INTO corpus_match (article_id, version, language_code, ruleid, rule_category, rule_subid, rule_description, message, error_context, small_error_context, corpus_date, check_date, source_type, replacement_suggestion, is_visible)" +
          " VALUES (?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)");
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    protected void handleResult(Sentence sentence, List<RuleMatch> ruleMatches, Language language) {
      try {
        java.sql.Date nowDate = new java.sql.Date(new java.util.Date().getTime());
        List<RuleMatch> rulesMatchesWithSuggestions = ruleMatches.stream()
          .filter(match -> !match.getSuggestedReplacements().isEmpty())
          .collect(Collectors.toList());

        long articleId = createOrRetrieveArticle(sentence);

        for (RuleMatch match : rulesMatchesWithSuggestions) {
          String context = contextTools.getContext(match.getFromPos(), match.getToPos(), sentence.getText());
          if (context.length() > MAX_CONTEXT_LENGTH) {
            // let's skip these strange cases, as shortening the text might leave us behind with invalid markup etc
            continue;
          }

          String suggestion = match.getSuggestedReplacements().get(0);
          List<String> urlParts = Arrays.asList(sentence.getUrl().split("/"));
          String urlWithoutRevision = StringUtils.join(urlParts.subList(0, urlParts.size()-1), "/");
          HtmlTools.HtmlAnonymizer articleAnonymizer = WikipediaSentenceSource.anonymizedArticles.get(urlWithoutRevision);

          try {
            HtmlTools.getTextWithAppliedSuggestion(articleAnonymizer.getTitle(), articleAnonymizer.getWikitext(), context, suggestion);

            String smallContext = smallContextTools.getContext(match.getFromPos(), match.getToPos(), sentence.getText());

            addSentenceToBatch(articleId, sentence.getSource(), language, nowDate, match, context, smallContext);
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
          catch(HtmlTools.SuggestionNotApplicableException e) {
            System.out.println("Can't apply suggestion : " + e.getMessage());
          }
        }
        checkMaxSentences(++sentenceCount);
      } catch (DocumentLimitReachedException | ErrorLimitReachedException e) {
        throw e;
      } catch (Exception e) {
        throw new RuntimeException("Error storing matches for '" + sentence.getTitle() + "'", e);
      }
    }

    private long createOrRetrieveArticle(Sentence sentence) throws SQLException {
      List<String> urlParts = Arrays.asList(sentence.getUrl().split("/"));
      String urlWithoutRevision = StringUtils.join(urlParts.subList(0, urlParts.size()-1), "/");
      int revision = Integer.parseInt(urlParts.get(urlParts.size()-1));

      HtmlTools.HtmlAnonymizer anonymizer = WikipediaSentenceSource.anonymizedArticles.get(urlWithoutRevision);
      if (processedAnonymizedArticles != null && !processedAnonymizedArticles.contains(urlWithoutRevision)) {
        insertCorpusArticleSt.setString(1, anonymizer.getTitle());
        insertCorpusArticleSt.setInt(2, revision);
        insertCorpusArticleSt.setString(3, anonymizer.getWikitext());
        insertCorpusArticleSt.setString(4, anonymizer.getAnonymizedHtml());

        if (insertCorpusArticleSt.executeUpdate() == 0) {
          throw new SQLException("Creating article failed, no rows affected.");
        }

        ResultSet generatedKeys = insertCorpusArticleSt.getGeneratedKeys();
        if (generatedKeys.next()) {
          anonymizer.setArticleId(generatedKeys.getLong(1));
        }
        else {
          throw new SQLException("Creating article failed, no ID obtained.");
        }

        processedAnonymizedArticles.add(urlWithoutRevision);
      }
      return anonymizer.getArticleId();
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
}
