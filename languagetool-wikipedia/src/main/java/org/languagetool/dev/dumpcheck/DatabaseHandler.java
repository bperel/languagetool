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
import org.languagetool.tools.HtmlTools.HtmlAnonymizer.HtmlAttribute;
import org.languagetool.tools.HtmlTools.HtmlAnonymizer.HtmlNode;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.*;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Store rule matches to a database.
 * @since 2.4
 */
abstract class DatabaseHandler extends ResultHandler {

  protected static Connection conn;
  protected static int batchSize;

  protected PreparedStatement insertCorpusMatchSt;
  
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

    private final PreparedStatement insertHtmlNodeSt;
    private final PreparedStatement insertHtmlAttributeSt;

    private List<String> processedAnonymizedArticles = new ArrayList<>();

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
        insertCorpusMatchSt = conn.prepareStatement("" +
          " INSERT INTO corpus_match (version, language_code, ruleid, rule_category, rule_subid, rule_description, message, error_context, small_error_context, corpus_date, check_date, sourceuri, source_type, replacement_suggestion, is_visible)" +
          " VALUES (0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)");
        insertHtmlNodeSt = conn.prepareStatement("" +
          " INSERT INTO html_node (source_uri, xpath, tag_name)" +
          " VALUES (?, ? ,?)");
        insertHtmlAttributeSt = conn.prepareStatement("" +
          " INSERT INTO html_attribute (source_uri, node_xpath, attribute_name, attribute_value)" +
          " VALUES (?, ?, ?, ?)");
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    protected void handleResult(Sentence sentence, List<RuleMatch> ruleMatches, Language language) {
      try {
        java.sql.Date nowDate = new java.sql.Date(new Date().getTime());
        List<RuleMatch> rulesMatchesWithSuggestions = ruleMatches.stream()
          .filter(match -> !match.getSuggestedReplacements().isEmpty())
          .collect(Collectors.toList());
        for (RuleMatch match : rulesMatchesWithSuggestions) {
          String context = contextTools.getContext(match.getFromPos(), match.getToPos(), sentence.getText());
          if (context.length() > MAX_CONTEXT_LENGTH) {
            // let's skip these strange cases, as shortening the text might leave us behind with invalid markup etc
            continue;
          }

          String smallContext = smallContextTools.getContext(match.getFromPos(), match.getToPos(), sentence.getText());

          addSentenceToBatch(sentence, language, nowDate, match, context, smallContext);
          if (++batchCount >= batchSize){
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

    private void addSentenceToBatch(Sentence sentence, Language language, java.sql.Date nowDate, RuleMatch match, String context, String smallContext) throws SQLException {
      Rule rule = match.getRule();
      insertCorpusMatchSt.setString(1, language.getShortCode());
      insertCorpusMatchSt.setString(2, rule.getId());
      insertCorpusMatchSt.setString(3, rule.getCategory().getName());
      if (rule instanceof AbstractPatternRule) {
        insertCorpusMatchSt.setString(4, ((AbstractPatternRule) rule).getSubId());
      } else {
        insertCorpusMatchSt.setNull(4, Types.VARCHAR);
      }
      insertCorpusMatchSt.setString(5, rule.getDescription());
      insertCorpusMatchSt.setString(6, StringUtils.abbreviate(match.getMessage(), 255));
      insertCorpusMatchSt.setString(7, context);
      insertCorpusMatchSt.setString(8, StringUtils.abbreviate(smallContext, 255));

      insertCorpusMatchSt.setDate(9, nowDate);  // should actually be the dump's date, but isn't really used anyway...
      insertCorpusMatchSt.setDate(10, nowDate);
      insertCorpusMatchSt.setString(11, sentence.getUrl());
      insertCorpusMatchSt.setString(12, sentence.getSource());
      insertCorpusMatchSt.setString(13, match.getSuggestedReplacements().get(0));
      insertCorpusMatchSt.addBatch();

      String urlWithoutRevision = sentence.getUrl().replaceAll("/[^/]+$", "");
      HtmlTools.HtmlAnonymizer anonymizer = WikipediaSentenceSource.anonymizedArticles.get(urlWithoutRevision);
      if (processedAnonymizedArticles != null && !processedAnonymizedArticles.contains(urlWithoutRevision)) {
        for (HtmlNode node : anonymizer.getHtmlNodes()) {
          insertHtmlNodeSt.setString(1, sentence.getUrl());
          insertHtmlNodeSt.setString(2, node.getXpath());
          insertHtmlNodeSt.setString(3, node.getTagName());
          insertHtmlNodeSt.addBatch();
        }
        for (HtmlAttribute attribute : anonymizer.getHtmlAttributes()) {
          insertHtmlAttributeSt.setString(1, sentence.getUrl());
          insertHtmlAttributeSt.setString(2, attribute.getXpath());
          insertHtmlAttributeSt.setString(3, attribute.getName());
          insertHtmlAttributeSt.setString(4, attribute.getValue());
          insertHtmlAttributeSt.addBatch();
        }
        processedAnonymizedArticles.add(sentence.getUrl());
      }
    }

    void executeBatch() throws SQLException {
      boolean autoCommit = conn.getAutoCommit();
      conn.setAutoCommit(false);
      try {
        insertCorpusMatchSt.executeBatch();
        insertHtmlNodeSt.executeBatch();
        insertHtmlAttributeSt.executeBatch();
        if (autoCommit) {
          conn.commit();
        }
      } finally {
        conn.setAutoCommit(autoCommit);
      }
    }

    @Override
    public void close() throws Exception {
      if (insertCorpusMatchSt != null) {
        if (batchCount > 0) {
          executeBatch();
        }
        insertCorpusMatchSt.close();
      }
      if (conn != null) {
        conn.close();
      }
    }
  }
}
