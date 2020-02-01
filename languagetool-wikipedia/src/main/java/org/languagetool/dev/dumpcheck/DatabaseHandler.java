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
import java.util.*;
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
        insertCorpusMatchSt = conn.prepareStatement("" +
          " INSERT INTO corpus_match (version, language_code, ruleid, rule_category, rule_subid, rule_description, message, error_context, small_error_context, corpus_date, check_date, source_uri, revision, source_type, replacement_suggestion, is_visible)" +
          " VALUES (0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)");
        insertHtmlNodeSt = conn.prepareStatement("" +
          " INSERT INTO html_node (source_uri, revision, parent_id, child_index, tag_name)" +
          " VALUES (?, ? ,?, ?, ?)");
        insertHtmlAttributeSt = conn.prepareStatement("" +
          " INSERT INTO html_attribute (source_uri, revision, parent_id, child_index, attribute_name, attribute_value)" +
          " VALUES (?, ?, ?, ?, ?, ?)");
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

        List<String> urlParts = Arrays.asList(sentence.getUrl().split("/"));
        String urlWithoutRevision = StringUtils.join(urlParts.subList(0, urlParts.size()-1), "/");
        int revision = Integer.parseInt(urlParts.get(urlParts.size()-1));

        HtmlTools.HtmlAnonymizer anonymizer = WikipediaSentenceSource.anonymizedArticles.get(urlWithoutRevision);
        if (processedAnonymizedArticles != null && !processedAnonymizedArticles.contains(urlWithoutRevision)) {
          for (HtmlNode node : anonymizer.getHtmlNodes()) {
            insertHtmlNodeSt.setString(1, urlWithoutRevision);
            insertHtmlNodeSt.setInt(2, revision);
            if (node.getParentId() == null) {
              insertHtmlNodeSt.setNull(3, Types.INTEGER);
            } else {
              insertHtmlNodeSt.setInt(3, node.getParentId());
            }
            insertHtmlNodeSt.setInt(4, node.getChildIndex());
            insertHtmlNodeSt.setString(5, node.getTagName());
            insertHtmlNodeSt.addBatch();
          }
          for (HtmlAttribute attribute : anonymizer.getHtmlAttributes()) {
            insertHtmlAttributeSt.setString(1, urlWithoutRevision);
            insertHtmlAttributeSt.setInt(2, revision);
            if (attribute.getParentId() == null) {
              insertHtmlAttributeSt.setNull(3, Types.INTEGER);
            } else {
              insertHtmlAttributeSt.setInt(3, attribute.getParentId());
            }
            insertHtmlAttributeSt.setInt(4, attribute.getChildIndex());
            insertHtmlAttributeSt.setString(5, attribute.getName());
            insertHtmlAttributeSt.setString(6, attribute.getValue());
            insertHtmlAttributeSt.addBatch();
          }
          processedAnonymizedArticles.add(urlWithoutRevision);
        }

        for (RuleMatch match : rulesMatchesWithSuggestions) {
          String context = contextTools.getContext(match.getFromPos(), match.getToPos(), sentence.getText());
          if (context.length() > MAX_CONTEXT_LENGTH) {
            // let's skip these strange cases, as shortening the text might leave us behind with invalid markup etc
            continue;
          }

          String smallContext = smallContextTools.getContext(match.getFromPos(), match.getToPos(), sentence.getText());

          addSentenceToBatch(urlWithoutRevision, revision, sentence.getSource(), language, nowDate, match, context, smallContext);
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

    private void addSentenceToBatch(String urlWithoutRevision, Integer revision, String source, Language language, java.sql.Date nowDate, RuleMatch match, String context, String smallContext) throws SQLException {

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
      insertCorpusMatchSt.setString(11, urlWithoutRevision);
      insertCorpusMatchSt.setInt(12, revision);
      insertCorpusMatchSt.setString(13, source);
      insertCorpusMatchSt.setString(14, match.getSuggestedReplacements().get(0));
      insertCorpusMatchSt.addBatch();
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
      for (PreparedStatement preparedStatement : Arrays.asList(insertCorpusMatchSt, insertHtmlNodeSt, insertHtmlAttributeSt)) {
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
