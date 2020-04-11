/* LanguageTool, a natural language style checker
 * Copyright (C) 2016 Daniel Naber (http://www.danielnaber.de)
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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.scribejava.core.model.OAuth1AccessToken;
import com.sun.net.httpserver.HttpExchange;
import org.jetbrains.annotations.NotNull;
import org.languagetool.JLanguageTool;
import org.languagetool.Language;
import org.languagetool.Languages;
import org.languagetool.dev.wikipedia.MediaWikiApi;
import org.languagetool.markup.AnnotatedText;
import org.languagetool.markup.AnnotatedTextBuilder;
import org.languagetool.rules.CorrectExample;
import org.languagetool.rules.IncorrectExample;
import org.languagetool.rules.Rule;
import org.languagetool.rules.TextLevelRule;
import org.languagetool.server.DatabaseAccess.ContributionStatisticsPerMonth;
import org.languagetool.server.DatabaseAccess.DayStatistics;
import org.languagetool.tools.HtmlTools;
import org.languagetool.tools.HtmlTools.SuggestionNotApplicableException;

import java.io.IOException;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.util.*;
import java.util.concurrent.ExecutionException;

import static org.languagetool.server.LanguageToolHttpHandler.API_DOC_URL;

/**
 * Handle requests to {@code /v2/} of the HTTP API. 
 * @since 3.4
 */
class ApiV2 {

  private static final String JSON_CONTENT_TYPE = "application/json";
  private static final String TEXT_CONTENT_TYPE = "text/plain";
  private static final String ENCODING = "UTF-8";

  private final TextChecker textChecker;
  private final String allowOriginUrl;
  private final JsonFactory factory = new JsonFactory();

  ApiV2(TextChecker textChecker, String allowOriginUrl) {
    this.textChecker = textChecker;
    this.allowOriginUrl = allowOriginUrl;
  }

  void handleRequest(String path, HttpExchange httpExchange, Map<String, String> parameters, ErrorRequestLimiter errorRequestLimiter, String remoteAddress, HTTPServerConfig config) throws Exception {
//    if (path.equals("languages")) {
//      handleLanguagesRequest(httpExchange);
//    } else if (path.equals("maxtextlength")) {
//      handleMaxTextLengthRequest(httpExchange, config);
//    } else if (path.equals("configinfo")) {
//      handleGetConfigurationInfoRequest(httpExchange, parameters, config);
//    } else if (path.equals("info")) {
//      handleSoftwareInfoRequest(httpExchange, parameters, config);
//    } else if (path.equals("check")) {
//      handleCheckRequest(httpExchange, parameters, errorRequestLimiter, remoteAddress);
//    } else if (path.equals("words")) {
//      handleWordsRequest(httpExchange, parameters, config);
//    } else if (path.equals("words/add")) {
//      handleWordAddRequest(httpExchange, parameters, config);
//    } else if (path.equals("words/delete")) {
//      handleWordDeleteRequest(httpExchange, parameters, config);
//    } else if (path.equals("rule/examples")) {
//      // private (i.e. undocumented) API for our own use only
//      handleRuleExamplesRequest(httpExchange, parameters);
//    } else if (path.equals("log")) {
//      // private (i.e. undocumented) API for our own use only
//      handleLogRequest(httpExchange, parameters);
    if (path.equals("wikipedia/authorize")) {
      handleWikipediaAuthorizeRequest(httpExchange, parameters);
    } else if (path.equals("wikipedia/login")) {
      handleWikipediaLoginRequest(httpExchange, parameters);
    } else if (path.equals("wikipedia/user")) {
      handleWikipediaUserRequest(httpExchange, parameters);
    } else if (path.equals("wikipedia/suggestions")) {
      handleWikipediaSuggestionRequest(httpExchange, parameters);
    } else if (path.equals("wikipedia/suggestion")) {
      handleWikipediaSuggestionDetailsRequest(httpExchange, parameters);
    } else if (path.equals("wikipedia/suggestion/accept")) {
      handleWikipediaAcceptRequest(httpExchange, parameters);
    } else if (path.equals("wikipedia/suggestion/refuse")) {
      handleWikipediaRefuseRequest(httpExchange, parameters);
    } else if (path.equals("wikipedia/stats")) {
      handleWikipediaStatsRequest(httpExchange);
    } else {
      throw new PathNotFoundException("Unsupported action: '" + path + "'. Please see " + API_DOC_URL);
    }
  }

  private void handleLanguagesRequest(HttpExchange httpExchange) throws IOException {
    String response = getLanguages();
    ServerTools.setCommonHeaders(httpExchange, JSON_CONTENT_TYPE, allowOriginUrl);
    httpExchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, response.getBytes(ENCODING).length);
    httpExchange.getResponseBody().write(response.getBytes(ENCODING));
    ServerMetricsCollector.getInstance().logResponse(HttpURLConnection.HTTP_OK);
  }

  private void handleMaxTextLengthRequest(HttpExchange httpExchange, HTTPServerConfig config) throws IOException {
    String response = Integer.toString(config.maxTextLength);
    ServerTools.setCommonHeaders(httpExchange, TEXT_CONTENT_TYPE, allowOriginUrl);
    httpExchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, response.getBytes(ENCODING).length);
    httpExchange.getResponseBody().write(response.getBytes(ENCODING));
    ServerMetricsCollector.getInstance().logResponse(HttpURLConnection.HTTP_OK);
  }

  private void handleGetConfigurationInfoRequest(HttpExchange httpExchange, Map<String, String> parameters, HTTPServerConfig config) throws IOException {
    if (parameters.get("language") == null) {
      throw new IllegalArgumentException("'language' parameter missing");
    }
    Language lang = Languages.getLanguageForShortCode(parameters.get("language"));
    String response = getConfigurationInfo(lang, config);
    ServerTools.setCommonHeaders(httpExchange, JSON_CONTENT_TYPE, allowOriginUrl);
    httpExchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, response.getBytes(ENCODING).length);
    httpExchange.getResponseBody().write(response.getBytes(ENCODING));
    ServerMetricsCollector.getInstance().logResponse(HttpURLConnection.HTTP_OK);
  }

  private void handleSoftwareInfoRequest(HttpExchange httpExchange, Map<String, String> parameters, HTTPServerConfig config) throws IOException {
    String response = getSoftwareInfo();
    ServerTools.setCommonHeaders(httpExchange, JSON_CONTENT_TYPE, allowOriginUrl);
    httpExchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, response.getBytes(ENCODING).length);
    httpExchange.getResponseBody().write(response.getBytes(ENCODING));
    ServerMetricsCollector.getInstance().logResponse(HttpURLConnection.HTTP_OK);
  }

  private void handleCheckRequest(HttpExchange httpExchange, Map<String, String> parameters, ErrorRequestLimiter errorRequestLimiter, String remoteAddress) throws Exception {
    AnnotatedText aText;
    if (parameters.containsKey("text") && parameters.containsKey("data")) {
      throw new IllegalArgumentException("Set only 'text' or 'data' parameter, not both");
    } else if (parameters.containsKey("text")) {
      aText = new AnnotatedTextBuilder().addText(parameters.get("text")).build();
    } else if (parameters.containsKey("data")) {
      ObjectMapper mapper = new ObjectMapper();
      JsonNode data = mapper.readTree(parameters.get("data"));
      if (data.get("text") != null && data.get("annotation") != null) {
        throw new IllegalArgumentException("'data' key in JSON requires either 'text' or 'annotation' key, not both");
      } else if (data.get("text") != null) {
        aText = getAnnotatedTextFromString(data, data.get("text").asText());
      } else if (data.get("annotation") != null) {
        aText = getAnnotatedTextFromJson(data);
      } else {
        throw new IllegalArgumentException("'data' key in JSON requires 'text' or 'annotation' key");
      }
    } else {
      throw new IllegalArgumentException("Missing 'text' or 'data' parameter");
    }
    textChecker.checkText(aText, httpExchange, parameters, errorRequestLimiter, remoteAddress);
  }

  private void handleWordsRequest(HttpExchange httpExchange, Map<String, String> params, HTTPServerConfig config) throws Exception {
    ensureGetMethod(httpExchange, "/words");
    UserLimits limits = getUserLimits(params, config);
    DatabaseAccess db = DatabaseAccess.getInstance();
    int offset = params.get("offset") != null ? Integer.parseInt(params.get("offset")) : 0;
    int limit = params.get("limit") != null ? Integer.parseInt(params.get("limit")) : 10;
    List<UserDictEntry> words = db.getWords(limits.getPremiumUid(), offset, limit);
    writeListResponse("words", words, httpExchange);
  }
  
  private void handleWordAddRequest(HttpExchange httpExchange, Map<String, String> parameters, HTTPServerConfig config) throws Exception {
    ensurePostMethod(httpExchange, "/words/add");
    UserLimits limits = getUserLimits(parameters, config);
    DatabaseAccess db = DatabaseAccess.getInstance();
    boolean added = db.addWord(parameters.get("word"), limits.getPremiumUid());
    writeBooleanResponse("added", added, httpExchange);
  }

  private void handleWordDeleteRequest(HttpExchange httpExchange, Map<String, String> parameters, HTTPServerConfig config) throws Exception {
    ensurePostMethod(httpExchange, "/words/delete");
    UserLimits limits = getUserLimits(parameters, config);
    DatabaseAccess db = DatabaseAccess.getInstance();
    boolean deleted = db.deleteWord(parameters.get("word"), limits.getPremiumUid());
    writeBooleanResponse("deleted", deleted, httpExchange);
  }

  private void handleWikipediaAuthorizeRequest(HttpExchange httpExchange, Map<String, String> parameters) throws IOException {
    ensureGetMethod(httpExchange, "/wikipedia/authorize");
    String languageCode = parameters.get("languageCode");

    MediaWikiApi mediaWikiApi = new MediaWikiApi(languageCode);
    String requestToken;
    String authorizationUrl;
    try {
      requestToken = mediaWikiApi.authorize();
      authorizationUrl = mediaWikiApi.getAuthorizationUrl(requestToken);
    } catch (InterruptedException|ExecutionException e) {
      writeStringError("Failed to login : " + e.getMessage(), httpExchange);
      return;
    }

    HashMap<String, String> responseFields = new HashMap<>();
    responseFields.put("authorizationUrl", authorizationUrl);
    responseFields.put("requestToken", requestToken);

    writeStringHashMapResponse(responseFields, httpExchange);
  }

  private void handleWikipediaLoginRequest(HttpExchange httpExchange, Map<String, String> parameters) throws IOException {
    ensureGetMethod(httpExchange, "/wikipedia/login");
    String requestToken = parameters.get("requestToken");
    String languageCode = parameters.get("languageCode");
    String oAuthVerifier = parameters.get("oauth_verifier");

    MediaWikiApi mediaWikiApi = new MediaWikiApi(languageCode);
    try {
      mediaWikiApi.login(requestToken, oAuthVerifier);
      OAuth1AccessToken accessTokenWithSecret = mediaWikiApi.getAccessTokenWithSecret();
      String username = mediaWikiApi.getUsernameFromAccessToken();

      DatabaseAccess db = DatabaseAccess.getInstance();
      AccessToken existingToken = db.getAccessToken(languageCode, accessTokenWithSecret.getToken());
      if (existingToken == null) {
        db.createAccessToken(languageCode, username, accessTokenWithSecret.getToken(), accessTokenWithSecret.getTokenSecret());
      }
    } catch (InterruptedException|ExecutionException e) {
      writeStringError("Failed to login : " + e.getMessage(), httpExchange);
      return;
    }

    HashMap<String, String> fields = new HashMap<>();
    fields.put("accessToken", mediaWikiApi.getAccessTokenWithSecret().getToken());
    fields.put("languageCode", languageCode);
    writeStringHashMapResponse(fields, httpExchange);
  }

  private void handleWikipediaUserRequest(HttpExchange httpExchange, Map<String, String> parameters) throws IOException {
    ensureGetMethod(httpExchange, "/wikipedia/user");
    String languageCode = parameters.get("languageCode");
    AccessToken accessTokenData = getAccessTokenData(parameters.get("languageCode"), parameters.get("accessToken"));

    HashMap<String, String> fields = new HashMap<>();
    fields.put("userName", accessTokenData.getUsername());
    fields.put("languageCode", languageCode);
    writeStringHashMapResponse(fields, httpExchange);
  }

  private void handleWikipediaSuggestionRequest(HttpExchange httpExchange, Map<String, String> parameters) throws IOException {
    ensureGetMethod(httpExchange, "/wikipedia/suggestions");
    String[] languageCodes = parameters.get("languageCodes").split(",");

    DatabaseAccess db = DatabaseAccess.getInstance();
    List<CorpusMatchEntry> suggestions = db.getCorpusMatches(Arrays.asList(languageCodes), 10);

    HashMap<Integer, CorpusArticleEntry> articles = new HashMap<>();
    for(CorpusMatchEntry suggestion : suggestions) {
      articles.put(suggestion.getArticleId(), db.getCorpusArticle(suggestion.getArticleId()));
    }
    writeCorpusMatchListResponse(suggestions, articles, httpExchange);
  }

  private void handleWikipediaSuggestionDetailsRequest(HttpExchange httpExchange, Map<String, String> parameters) throws IOException {
    ensureGetMethod(httpExchange, "/wikipedia/suggestion");
    int suggestionId = Integer.parseInt(parameters.get("suggestion_id"));
    List<String> originalAndSuggestedWikitext;
    try {
      originalAndSuggestedWikitext = getOriginalAndSuggestedWikitext(suggestionId);
    } catch (SuggestionNotApplicableException e) {
      writeStringError("Suggestion not applicable : " + e.getMessage(), httpExchange);
      return;
    }

    writeSuggestionDetailsResponse("suggestion", originalAndSuggestedWikitext, httpExchange);
  }

  private void handleWikipediaAcceptRequest(HttpExchange httpExchange, Map<String, String> parameters) throws Exception {
    ensurePostMethod(httpExchange, "/wikipedia/accept");
    ServerTools.setCommonHeaders(httpExchange, JSON_CONTENT_TYPE, allowOriginUrl);
    int suggestionId = Integer.parseInt(parameters.get("suggestion_id"));
    AccessToken accessTokenData = getAccessTokenData(parameters.get("languageCode"), parameters.get("accessToken"));

    DatabaseAccess db = DatabaseAccess.getInstance();
    CorpusMatchEntry suggestion = db.getCorpusMatch(suggestionId);
    CorpusArticleEntry article = db.getCorpusArticle(suggestion.getArticleId());

    MediaWikiApi mediaWikiApi = new MediaWikiApi(article.getLanguageCode(), accessTokenData.getAccessToken(), accessTokenData.getAccessTokenSecret());
    String username = accessTokenData.getUsername();
    try {
      String articleWikitext = mediaWikiApi.getPage(article.getTitle());
      String contentWithSuggestionApplied = HtmlTools.getArticleWithAppliedSuggestion(
        article.getTitle(),
        articleWikitext,
        suggestion.getErrorContext(),
        suggestion.getReplacementSuggestion()
      );
      mediaWikiApi.edit(article.getTitle(), contentWithSuggestionApplied, suggestion.getRuleCategory());
    } catch (InterruptedException|ExecutionException e) {
      writeStringError("Failed to edit : " + e.getMessage(), httpExchange);
      return;
    } catch (SuggestionNotApplicableException e) {
      boolean refused = db.resolveCorpusMatch(suggestionId, username, false, "original-string-not-found");
      writeBooleanResponse("refused", refused, httpExchange);
      return;
    }

    boolean accepted = db.resolveCorpusMatch(suggestionId, username, true, null);

    writeBooleanResponse("accepted", accepted, httpExchange);
  }

  private void handleWikipediaRefuseRequest(HttpExchange httpExchange, Map<String, String> parameters) throws Exception {
    ensurePostMethod(httpExchange, "/wikipedia/refuse");
    ServerTools.setCommonHeaders(httpExchange, JSON_CONTENT_TYPE, allowOriginUrl);
    int suggestionId = Integer.parseInt(parameters.get("suggestion_id"));
    String reason = parameters.get("reason");

    if (!Arrays.asList("false-positive", "false-correction", "too-little-context", "should-be-ignored", "other").contains(reason)) {
      throw new RuntimeException("Invalid refusal reason : " + reason);
    }

    AccessToken accessTokenData = getAccessTokenData(parameters.get("languageCode"), parameters.get("accessToken"));
    String username = accessTokenData.getUsername();

    DatabaseAccess db = DatabaseAccess.getInstance();
    boolean refused = db.resolveCorpusMatch(suggestionId, username, false, reason);

    writeBooleanResponse("refused", refused, httpExchange);
  }

  private void handleWikipediaStatsRequest(HttpExchange httpExchange) throws Exception {
    ensureGetMethod(httpExchange, "/wikipedia/stats");
    ServerTools.setCommonHeaders(httpExchange, JSON_CONTENT_TYPE, allowOriginUrl);

    DatabaseAccess db = DatabaseAccess.getInstance();
    writeStatsListResponse(db.getDecisionStats(), db.getContributorsStats(), httpExchange);
  }

  private List<String> getOriginalAndSuggestedWikitext(int suggestionId) throws SuggestionNotApplicableException {
    DatabaseAccess db = DatabaseAccess.getInstance();
    CorpusMatchEntry suggestion = db.getCorpusMatch(suggestionId);
    if (suggestion == null) {
      throw new IllegalArgumentException("Suggestion not found : " + suggestionId);
    }

    CorpusArticleEntry article = db.getCorpusArticle(suggestion.getArticleId());

    return Arrays.asList(
      HtmlTools.getStringToReplace(HtmlTools.getLargestErrorContext(suggestion.getErrorContext())),
      HtmlTools.getErrorContextWithAppliedSuggestion(article.getTitle(), article.getWikitext(), suggestion.getErrorContext(), suggestion.getReplacementSuggestion())
    );
  }

  private AccessToken getAccessTokenData(String languageCode, String accessToken) throws RuntimeException {
    DatabaseAccess db = DatabaseAccess.getInstance();
    AccessToken accessTokenWithData = db.getAccessToken(languageCode, accessToken);
    if (accessTokenWithData == null) {
      throw new RuntimeException("Can't find access token");
    }
    return accessTokenWithData;
  }

  private void handleRuleExamplesRequest(HttpExchange httpExchange, Map<String, String> params) throws Exception {
    ensureGetMethod(httpExchange, "/rule/examples");
    if (params.get("lang") == null) {
      throw new IllegalArgumentException("'lang' parameter missing");
    }
    if (params.get("ruleId") == null) {
      throw new IllegalArgumentException("'ruleId' parameter missing");
    }
    Language lang = Languages.getLanguageForShortCode(params.get("lang"));
    JLanguageTool lt = new JLanguageTool(lang);
    if (textChecker.config.languageModelDir != null) {
      lt.activateLanguageModelRules(textChecker.config.languageModelDir);
    }
    List<Rule> rules = lt.getAllRules();
    List<Rule> foundRules = new ArrayList<>();
    for (Rule rule : rules) {
      if (rule.getId().equals(params.get("ruleId"))) {
        foundRules.add(rule);
      }
    }
    if (foundRules.isEmpty()) {
      throw new PathNotFoundException("Rule '" + params.get("ruleId") + "' not found for language " + lang +
              " (LanguageTool version/date: " + JLanguageTool.VERSION + "/" + JLanguageTool.BUILD_DATE + ", total rules of language: " + rules.size() + ")");
    }
    StringWriter sw = new StringWriter();
    try (JsonGenerator g = factory.createGenerator(sw)) {
      g.writeStartObject();
      g.writeArrayFieldStart("results");
      g.writeStartObject();
      g.writeStringField("warning", "*** This is not a public API - it may change anytime ***");
      g.writeEndObject();
      for (Rule foundRule : foundRules) {
        for (CorrectExample example : foundRule.getCorrectExamples()) {
          g.writeStartObject();
          g.writeStringField("status", "correct");
          g.writeStringField("sentence", example.getExample());
          g.writeEndObject();
        }
        for (IncorrectExample example : foundRule.getIncorrectExamples()) {
          g.writeStartObject();
          g.writeStringField("status", "incorrect");
          g.writeStringField("sentence", example.getExample());
          g.writeArrayFieldStart("corrections");
          for (String s : example.getCorrections()) {
            g.writeString(s);
          }
          g.writeEndArray();
          g.writeEndObject();
        }
      }
      g.writeEndArray();
      g.writeEndObject();
    }
    sendJson(httpExchange, sw);
  }

  private void ensureGetMethod(HttpExchange httpExchange, String url) {
    if (!httpExchange.getRequestMethod().equalsIgnoreCase("get")) {
      throw new IllegalArgumentException(url + " needs to be called with GET");
    }
  }
  
  private void ensurePostMethod(HttpExchange httpExchange, String url) throws IOException {
    if (httpExchange.getRequestMethod().equalsIgnoreCase("options")) {
      httpExchange.getResponseHeaders().add("Access-Control-Allow-Origin", allowOriginUrl);
      httpExchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
      httpExchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Authorization");
      httpExchange.sendResponseHeaders(204, -1);
    }
    else if (!httpExchange.getRequestMethod().equalsIgnoreCase("post")) {
      throw new IllegalArgumentException(url + " needs to be called with POST");
    }
  }

  @NotNull
  private UserLimits getUserLimits(Map<String, String> parameters, HTTPServerConfig config) {
    UserLimits limits = ServerTools.getUserLimits(parameters, config);
    if (limits.getPremiumUid() == null) {
      throw new IllegalStateException("This end point needs a user id");
    }
    return limits;
  }

  private void writeBooleanResponse(String fieldName, boolean value, HttpExchange httpExchange) throws IOException {
    StringWriter sw = new StringWriter();
    try (JsonGenerator g = factory.createGenerator(sw)) {
      g.writeStartObject();
      g.writeBooleanField(fieldName, value);
      g.writeEndObject();
    }
    sendJson(httpExchange, sw);
  }

  private void writeStringResponse(String fieldName, String value, HttpExchange httpExchange) throws IOException {
    StringWriter sw = new StringWriter();
    try (JsonGenerator g = factory.createGenerator(sw)) {
      g.writeStartObject();
      g.writeStringField(fieldName, value);
      g.writeEndObject();
    }
    sendJson(httpExchange, sw);
  }

  private void writeStringError(String value, HttpExchange httpExchange) throws IOException {
    StringWriter sw = new StringWriter();
    try (JsonGenerator g = factory.createGenerator(sw)) {
      g.writeStartObject();
      g.writeStringField("error", value);
      g.writeEndObject();
    }
    sendErrorJson(httpExchange, sw);
  }

  private void writeStringHashMapResponse(HashMap<String, String> fields, HttpExchange httpExchange) throws IOException {
    StringWriter sw = new StringWriter();
    try (JsonGenerator g = factory.createGenerator(sw)) {
      g.writeStartObject();
      for (String key : fields.keySet()) {
        g.writeStringField(key, fields.get(key));
      }
      g.writeEndObject();
    }
    sendJson(httpExchange, sw);
  }

  private void writeCorpusMatchListResponse(List<CorpusMatchEntry> corpusMatchEntries, HashMap<Integer, CorpusArticleEntry> articles, HttpExchange httpExchange) throws IOException {
    StringWriter sw = new StringWriter();
    try (JsonGenerator g = factory.createGenerator(sw)) {
      g.setCodec(new ObjectMapper());
      g.writeStartObject();
      g.writeArrayFieldStart("suggestions");
      for (CorpusMatchEntry corpusMatchEntry : corpusMatchEntries) {
        g.writeStartObject();
        g.writeObjectField("suggestion", corpusMatchEntry);

        g.writeObjectFieldStart("article");
        CorpusArticleEntry article = articles.get(corpusMatchEntry.getArticleId());
        g.writeStringField("title", article.getTitle());
        g.writeStringField("languageCode", article.getLanguageCode());
        g.writeStringField("url", article.getUrl());
        g.writeEndObject();

        g.writeEndObject();
      }
      g.writeEndArray();
      g.writeEndObject();
    }
    sendJson(httpExchange, sw);
  }

  private void writeStatsListResponse(List<DayStatistics> decisionsStats, List<ContributionStatisticsPerMonth> contributorsStats, HttpExchange httpExchange) throws IOException {
    StringWriter sw = new StringWriter();
    try (JsonGenerator g = factory.createGenerator(sw)) {
      g.setCodec(new ObjectMapper());
      g.writeStartObject();
      g.writeArrayFieldStart("decisions");
      for (DayStatistics stat : decisionsStats) {
        g.writeStartObject();
        g.writeObjectField("date", stat.getDate());
        g.writeObjectField("applied", stat.getApplied());
        g.writeObjectField("count", stat.getCount());
        g.writeEndObject();
      }
      g.writeEndArray();
      g.writeArrayFieldStart("contributors");
      for (ContributionStatisticsPerMonth stat : contributorsStats) {
        g.writeStartObject();
        g.writeObjectField("month", stat.getDate());
        g.writeObjectField("language", stat.getLanguage());
        g.writeObjectField("username", stat.getUsername());
        g.writeObjectField("count", stat.getCount());
        g.writeEndObject();
      }
      g.writeEndArray();
      g.writeEndObject();
    }
    sendJson(httpExchange, sw);
  }

  private void writeSuggestionDetailsResponse(String fieldName, List<String> originalAndSuggestedWikitext, HttpExchange httpExchange) throws IOException {
    StringWriter sw = new StringWriter();
    try (JsonGenerator g = factory.createGenerator(sw)) {
      g.setCodec(new ObjectMapper());
      g.writeStartObject();
      g.writeStringField("originalWikitext", originalAndSuggestedWikitext.get(0));
      g.writeStringField("suggestedWikitext", originalAndSuggestedWikitext.get(1));
      g.writeEndObject();
    }
    sendJson(httpExchange, sw);
  }

  private void writeListResponse(String fieldName, List<UserDictEntry> words, HttpExchange httpExchange) throws IOException {
    StringWriter sw = new StringWriter();
    try (JsonGenerator g = factory.createGenerator(sw)) {
      g.writeStartObject();
      g.writeArrayFieldStart(fieldName);
      for (UserDictEntry word : words) {
        g.writeString(word.getWord());
      }
      g.writeEndArray();
      g.writeEndObject();
    }
    sendJson(httpExchange, sw);
  }

  private void sendJson(HttpExchange httpExchange, StringWriter sw) throws IOException {
    String response = sw.toString();
    ServerTools.setCommonHeaders(httpExchange, JSON_CONTENT_TYPE, allowOriginUrl);
    httpExchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, response.getBytes(ENCODING).length);
    httpExchange.getResponseBody().write(response.getBytes(ENCODING));
    ServerMetricsCollector.getInstance().logResponse(HttpURLConnection.HTTP_OK);
  }

  private void sendErrorJson(HttpExchange httpExchange, StringWriter sw) throws IOException {
    String response = sw.toString();
    ServerTools.setCommonHeaders(httpExchange, JSON_CONTENT_TYPE, allowOriginUrl);
    httpExchange.sendResponseHeaders(HttpURLConnection.HTTP_BAD_REQUEST, response.getBytes(ENCODING).length);
    httpExchange.getResponseBody().write(response.getBytes(ENCODING));
    ServerMetricsCollector.getInstance().logResponse(HttpURLConnection.HTTP_BAD_REQUEST);
  }

  private void handleLogRequest(HttpExchange httpExchange, Map<String, String> parameters) throws IOException {
    // used so the client (especially the browser add-ons) can report internal issues:
    String message = parameters.get("message");
    if (message != null && message.length() > 250) {
      message = message.substring(0, 250) + "...";
    }
    ServerTools.print("Log message from client: " + message + " - User-Agent: " + httpExchange.getRequestHeaders().getFirst("User-Agent"));
    String response = "OK";
    httpExchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, response.getBytes(ENCODING).length);
    httpExchange.getResponseBody().write(response.getBytes(ENCODING));
    ServerMetricsCollector.getInstance().logResponse(HttpURLConnection.HTTP_OK);
  }

  private AnnotatedText getAnnotatedTextFromString(JsonNode data, String text) {
    AnnotatedTextBuilder textBuilder = new AnnotatedTextBuilder().addText(text);
    if (data.has("metaData")) {
      JsonNode metaData = data.get("metaData");
      Iterator<String> it = metaData.fieldNames();
      while (it.hasNext()) {
        String key = it.next();
        String val = metaData.get(key).asText();
        try {
          AnnotatedText.MetaDataKey metaDataKey = AnnotatedText.MetaDataKey.valueOf(key);
          textBuilder.addGlobalMetaData(metaDataKey, val);
        } catch (IllegalArgumentException e) {
          textBuilder.addGlobalMetaData(key, val);
        }
      }
    }
    return textBuilder.build();
  }

  private AnnotatedText getAnnotatedTextFromJson(JsonNode data) {
    AnnotatedTextBuilder atb = new AnnotatedTextBuilder();
    // Expected format:
    // annotation: [
    //   {text: 'text'},
    //   {markup: '<b>'}
    //   {text: 'more text'},
    //   {markup: '</b>'}
    // ]
    //
    for (JsonNode node : data.get("annotation")) {
      if (node.get("text") != null && node.get("markup") != null) {
        throw new IllegalArgumentException("Only either 'text' or 'markup' are supported in an object in 'annotation' list, not both: " + node);
      } else if (node.get("text") != null && node.get("interpretAs") != null) {
        throw new IllegalArgumentException("'text' cannot be used with 'interpretAs' (only 'markup' can): " + node);
      } else if (node.get("text") != null) {
        atb.addText(node.get("text").asText());
      } else if (node.get("markup") != null) {
        if (node.get("interpretAs") != null) {
          atb.addMarkup(node.get("markup").asText(), node.get("interpretAs").asText());
        } else {
          atb.addMarkup(node.get("markup").asText());
        }
      } else {
        throw new IllegalArgumentException("Only 'text' and 'markup' are supported in 'annotation' list: " + node);
      }
    }
    return atb.build();
  }

  String getLanguages() throws IOException {
    StringWriter sw = new StringWriter();
    try (JsonGenerator g = factory.createGenerator(sw)) {
      g.writeStartArray();
      List<Language> languages = new ArrayList<>(Languages.get());
      languages.sort(Comparator.comparing(Language::getName));
      for (Language lang : languages) {
        g.writeStartObject();
        g.writeStringField("name", lang.getName());
        g.writeStringField("code", lang.getShortCode());
        g.writeStringField("longCode", lang.getShortCodeWithCountryAndVariant());
        g.writeEndObject();
      }
      g.writeEndArray();
    }
    return sw.toString();
  }

  String getSoftwareInfo() throws IOException {
    StringWriter sw = new StringWriter();
    try (JsonGenerator g = factory.createGenerator(sw)) {
      g.writeStartObject();
      g.writeObjectFieldStart("software");
      g.writeStringField("name", "LanguageTool");
      g.writeStringField("version", JLanguageTool.VERSION);
      g.writeStringField("buildDate", JLanguageTool.BUILD_DATE);
      g.writeStringField("commit", JLanguageTool.GIT_SHORT_ID);
      g.writeBooleanField("premium", JLanguageTool.isPremiumVersion());
      g.writeEndObject();
      g.writeEndObject();
    }
    return sw.toString();
  }

  String getConfigurationInfo(Language lang, HTTPServerConfig config) throws IOException {
    StringWriter sw = new StringWriter();
    JLanguageTool lt = new JLanguageTool(lang);
    if (textChecker.config.languageModelDir != null) {
      lt.activateLanguageModelRules(textChecker.config.languageModelDir);
    }
    if (textChecker.config.word2vecModelDir != null) {
      lt.activateWord2VecModelRules(textChecker.config.word2vecModelDir);
    }
    List<Rule> rules = lt.getAllRules();
    try (JsonGenerator g = factory.createGenerator(sw)) {
      g.writeStartObject();

      g.writeObjectFieldStart("software");
      g.writeStringField("name", "LanguageTool");
      g.writeStringField("version", JLanguageTool.VERSION);
      g.writeStringField("buildDate", JLanguageTool.BUILD_DATE);
      g.writeBooleanField("premium", JLanguageTool.isPremiumVersion());
      g.writeEndObject();
      
      g.writeObjectFieldStart("parameter");
      g.writeNumberField("maxTextLength", config.maxTextLength);
      g.writeEndObject();

      g.writeArrayFieldStart("rules");
      for (Rule rule : rules) {
        g.writeStartObject();
        g.writeStringField("ruleId", rule.getId());
        g.writeStringField("description", rule.getDescription());
        if(rule.isDictionaryBasedSpellingRule()) {
          g.writeStringField("isDictionaryBasedSpellingRule", "yes");
        }
        if(rule.isDefaultOff()) {
          g.writeStringField("isDefaultOff", "yes");
        }
        if(rule.isOfficeDefaultOff()) {
          g.writeStringField("isOfficeDefaultOff", "yes");
        }
        if(rule.isOfficeDefaultOn()) {
          g.writeStringField("isOfficeDefaultOn", "yes");
        }
        if(rule.hasConfigurableValue()) {
          g.writeStringField("hasConfigurableValue", "yes");
          g.writeStringField("configureText", rule.getConfigureText());
          g.writeStringField("maxConfigurableValue", Integer.toString(rule.getMaxConfigurableValue()));
          g.writeStringField("minConfigurableValue", Integer.toString(rule.getMinConfigurableValue()));
          g.writeStringField("defaultValue", Integer.toString(rule.getDefaultValue()));
        }
        g.writeStringField("categoryId", rule.getCategory().getId().toString());
        g.writeStringField("categoryName", rule.getCategory().getName());
        g.writeStringField("locQualityIssueType", rule.getLocQualityIssueType().toString());
        if(rule instanceof TextLevelRule) {
          g.writeStringField("isTextLevelRule", "yes");
          g.writeStringField("minToCheckParagraph", Integer.toString(((TextLevelRule) rule).minToCheckParagraph()));
        }
        g.writeEndObject();
      }
      g.writeEndArray();

      g.writeEndObject();
    }
    return sw.toString();
  }

}
