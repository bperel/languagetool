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
import org.jetbrains.annotations.NotNull;
import org.languagetool.Language;
import org.languagetool.dev.wikipedia.ParsoidWikipediaTextParser;
import org.languagetool.rules.RuleMatchWithHtmlContexts;
import org.languagetool.tokenizers.Tokenizer;
import org.languagetool.tools.HtmlTools;
import org.languagetool.tools.HtmlTools.HTMLParser;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Provides access to the sentences of a Wikipedia XML dump. Note that
 * conversion exceptions are logged to STDERR and are otherwise ignored.
 * 
 * To get an XML dump, download {@code pages-articles.xml.bz2} from
 * <a href="http://download.wikimedia.org/backup-index.html">http://download.wikimedia.org/backup-index.html</a>, e.g.
 * {@code http://download.wikimedia.org/dewiki/latest/dewiki-latest-pages-articles.xml.bz2}.
 * @since 2.4
 */
public class WikipediaSentenceSource extends SentenceSource {

  private static final boolean ONLY_ARTICLES = true;
  private static final String ARTICLE_NAMESPACE = "0";

  private final ParsoidWikipediaTextParser textParser;
  private final Tokenizer sentenceTokenizer;
  private final List<WikipediaSentence> sentences;
  private final Language language;
  private final CorpusMatchDatabaseHandler databaseHandler;

  private int articleCount = 0;
  private int namespaceSkipCount = 0;
  private int redirectSkipCount = 0;

  public int ruleMatchCount = 0;
  public int sentenceCount = 0;

  WikipediaSentenceSource(InputStream xmlInput, Language language) {
    this(xmlInput, language, null, null, null);
  }

  /** @since 3.0 */
  WikipediaSentenceSource(InputStream xmlInput, Language language, Pattern filter, String parsoidUrl, CorpusMatchDatabaseHandler databaseHandler) {
    super(language, filter);
    textParser = new ParsoidWikipediaTextParser(language.getShortCode(), parsoidUrl);
    sentenceTokenizer = language.getSentenceTokenizer();
    sentences = new ArrayList<>();
    this.databaseHandler = databaseHandler;
    this.language = language;

    try {
      System.setProperty("jdk.xml.totalEntitySizeLimit", String.valueOf(Integer.MAX_VALUE));  // see https://github.com/dbpedia/extraction-framework/issues/487

      SAXParserFactory factory = SAXParserFactory.newInstance();
      SAXParser saxParser = factory.newSAXParser();

      class ParseLimitExceededException extends SAXException {
        public ParseLimitExceededException(int articleCount) {
          super("Article read limit has been exceeded : " + articleCount);
        }
      }

      DefaultHandler handler = new DefaultHandler() {
        private String currentQName = null;
        private boolean isRevisionContext;

        private StringBuilder title;
        private StringBuilder namespace;
        private StringBuilder revisionId;
        private StringBuilder text;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
          currentQName = qName.toLowerCase();

          if (currentQName.equals("revision")) {
            if (articleCount > 200) {
              throw new ParseLimitExceededException(articleCount);
            }
            isRevisionContext = true;
          }
          else if (currentQName.equals("page") || currentQName.equals("contributor")) {
            isRevisionContext = false;
            if (currentQName.equals("page")) {
              title = new StringBuilder();
              namespace = new StringBuilder();
              revisionId = new StringBuilder();
              text = new StringBuilder();
            }
          }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
          if (qName.toLowerCase().equals("page")) {

            String title = this.title.toString().trim();

            String namespace = this.namespace.toString().trim();
            if (ONLY_ARTICLES && !ARTICLE_NAMESPACE.equals(namespace)) {
              namespaceSkipCount++;
              print("Article " + title + " skipped : it doesn't belong to the main namespace (namespace :" + namespace + ")");
              return;
            }

            String text = this.text.toString().trim();
            if (text.length() > 250000) {
              print("Article " + title + " skipped : it is too large (" + text.length() + "characters)");
              return;
            }

            int revisionId = Integer.parseInt(this.revisionId.toString().trim());
            try {
              Object[] article = databaseHandler.getAnalyzedArticle(title, language.getShortCode(), revisionId);
              boolean isAnalyzed = article != null && (Boolean) article[2];
              if (isAnalyzed) {
                Long existingRevisionId = (Long) article[1];
                if (existingRevisionId > revisionId) {
                  print("Article " + title + " skipped : the version in the DB (" + existingRevisionId + ") is more recent than this one (" + revisionId + ")");
                }
                else {
                  print("Article " + title + " skipped : it is already in the DB (revision " + revisionId + ")");
                }
              }
              else {
                articleCount++;
                print("Article " + title + " (#" + articleCount + ") : Starting analysis");
                if (article == null) {
                  article = addArticle(title, revisionId, text.trim());
                  if (article == null) {
                    return;
                  }
                }
                else {
                  print("Article " + title + " (#" + articleCount + ") : In the DB but not analysed. Starting analysis");
                }
                Long articleId = (Long) article[0];
                String wikitext = (String) article[3];
                String cssUrl = (String) article[4];
                String html = (String) article[5];
                String anonymizedHtml = (String) article[6];

                addSentencesFromArticle(articleId, title, revisionId, anonymizedHtml);

                databaseHandler.deleteNeverAppliedSuggestionsOfObsoleteArticles(title, language.getShortCode(), revisionId);
                processSentences(wikitext, cssUrl, html);
                databaseHandler.markArticleAsAnalyzed(articleId);
                databaseHandler.deleteAlreadyAppliedSuggestionsInNewArticleRevisions(articleId);
              }
            } catch (SQLException e) {
              e.printStackTrace();
            }
          }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
          String content = new String(ch, start, length);
          switch(currentQName) {
            case "title":
              title.append(content);
              break;
            case "ns":
              namespace.append(content);
              break;
            case "text":
              text.append(content);
              break;
            case "id":
              if (isRevisionContext) {
                revisionId.append(content);
              }
            break;
          }
        }
      };
      try {
        print("Parsing XML input...");
        saxParser.parse(xmlInput, handler);
        print("Done.");
      } catch (ParseLimitExceededException ignored) { }

    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new RuntimeException(e);
    } finally {
      float matchesPerSentence = (float)ruleMatchCount / sentenceCount;
      System.out.printf(language + ": %d total matches\n", ruleMatchCount);
      System.out.printf(Locale.ENGLISH, language + ": ø%.2f rule matches per sentence\n", matchesPerSentence);
      try {
        databaseHandler.close();
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }

  private void processSentences(String wikitext, String cssUrl, String html) {
    while (this.hasNext()) {
      Sentence sentence = this.next();
      try {
        List<RuleMatchWithHtmlContexts> matches = getMatches(sentence, wikitext, html, cssUrl);

        try {
          databaseHandler.handleResult(sentence, matches);
          sentenceCount++;
          ruleMatchCount += matches.size();
        }
        catch(SQLIntegrityConstraintViolationException ignored) { }
      } catch (DocumentLimitReachedException | ErrorLimitReachedException e) {
        System.out.println(getClass().getSimpleName() + ": " + e);
      } catch (Exception e) {
        System.out.println("Check failed on sentence: " + StringUtils.abbreviate(sentence.getText(), 250));
      }
    }
  }

  @Override
  public boolean hasNext() {
    return sentences.size() > 0;
  }

  @Override
  public Sentence next() {
    if (sentences.isEmpty()) {
      throw new NoSuchElementException();
    }
    WikipediaSentence wikiSentence = sentences.remove(0);
    String url = getUrl(wikiSentence.title, wikiSentence.revision);
    return new Sentence(wikiSentence.sentence, getSource(), wikiSentence.title, url, wikiSentence.articleId);
  }

  @NotNull
  private String getUrl(String title, Integer revision) {
    String url = MessageFormat.format("http://{0}.wikipedia.org/wiki/{1}", language.getShortCode(), title);
    if (revision != null) {
      url+="/"+revision;
    }
    return url;
  }

  @Override
  public String getSource() {
    return "wikipedia";
  }

  private Object[] addArticle(String title, Integer revisionId, String wikitext) {
    try {
      if (wikitext.trim().toLowerCase().startsWith("#redirect")) {
        redirectSkipCount++;
      }

      HtmlTools.HtmlAnonymizer htmlAnonymizer = textParser.convertWikitextToHtml(title, wikitext);
      if (htmlAnonymizer != null) {
        String html = htmlAnonymizer.getHtml();
        String anonymizedHtml = htmlAnonymizer.getAnonymizedHtml();
        String cssUrl = htmlAnonymizer.getCssUrl();
        Long articleId = databaseHandler.createArticle(language.getShortCode(), title, revisionId, wikitext, html, anonymizedHtml, cssUrl);

        return new Object[] { articleId, 0, false, wikitext, cssUrl, html, anonymizedHtml };
      }
    } catch (Exception e) {
      print("Could not extract text, skipping document: " + e + ", full stacktrace follows:");
      e.printStackTrace();
    }
    return null;
  }

  private void addSentencesFromArticle(Long articleId, String title, Integer revisionId, String anonymizedHtml) {
    for (String sentence : sentenceTokenizer.tokenize(anonymizedHtml)) {
      if (acceptSentence(sentence)) {
        sentences.add(new WikipediaSentence(sentence, title, revisionId, articleId));
      }
    }
  }

  public static List<RuleMatchWithHtmlContexts> getMatches(Sentence sentence, String articleWikitext, String articleHtml, String articleCssUrl) throws IOException {
    List<RuleMatchWithHtmlContexts> matches = MixingSentenceSource.lt.check(sentence.getText()).stream()
      .map(RuleMatchWithHtmlContexts.addTextContext(articleWikitext, sentence.getTitle(), sentence.getText()))
      .filter(Objects::nonNull).collect(Collectors.toList());

    if (!matches.isEmpty() && articleHtml != null) {
      RuleMatchWithHtmlContexts.languageCode = MixingSentenceSource.lt.getLanguage().getShortCode();
      RuleMatchWithHtmlContexts.currentArticleDocument = HTMLParser.parseArticle(articleHtml, sentence.getArticleId());
      RuleMatchWithHtmlContexts.currentArticleCssUrl = articleCssUrl;
      return matches.stream()
        .map(RuleMatchWithHtmlContexts.mapRuleAddHtmlContext())
        .filter(Objects::nonNull).collect(Collectors.toList());
    }
    return matches;
  }

  static void print(String s) {
    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS ZZ");
    dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    String now = dateFormat.format(new Date());
    System.out.println(now + " " + s);
  }

  static class WikipediaSentence {
    final String sentence;
    final String title;
    final Integer revision;
    final Long articleId;
    WikipediaSentence(String sentence, String title, Integer revision, Long articleId) {
      this.sentence = sentence;
      this.title = title;
      this.revision = revision;
      this.articleId = articleId;
    }
  }
}
