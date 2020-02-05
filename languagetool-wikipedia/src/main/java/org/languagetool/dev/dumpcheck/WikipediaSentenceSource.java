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

import org.jetbrains.annotations.NotNull;
import org.languagetool.Language;
import org.languagetool.dev.wikipedia.ParsoidWikipediaTextParser;
import org.languagetool.tokenizers.Tokenizer;
import org.languagetool.tools.HtmlTools;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;

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

  private static final boolean ONLY_ARTICLES = false;
  private static final String ARTICLE_NAMESPACE = "0";

  public static HashMap<String, HtmlTools.HtmlAnonymizer> anonymizedArticles = new HashMap<>();

  private final ParsoidWikipediaTextParser textFilter = new ParsoidWikipediaTextParser();
  //private final XMLEventReader reader;
  private Document doc;
  private final Tokenizer sentenceTokenizer;
  private final List<WikipediaSentence> sentences;
  private final Language language;

  private int articleCount = 0;
  private int namespaceSkipCount = 0;
  private int redirectSkipCount = 0;

  WikipediaSentenceSource(InputStream xmlInput, Language language) {
    this(xmlInput, language, null);
  }

  /** @since 3.0 */
  WikipediaSentenceSource(InputStream xmlInput, Language language, Pattern filter) {
    super(language, filter);
    try {
      System.setProperty("jdk.xml.totalEntitySizeLimit", String.valueOf(Integer.MAX_VALUE));  // see https://github.com/dbpedia/extraction-framework/issues/487
      DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
      dbf.setValidating(false);
      DocumentBuilder db = dbf.newDocumentBuilder();

      doc = db.parse(new InputSource(xmlInput));
      sentenceTokenizer = language.getSentenceTokenizer();
      sentences = new ArrayList<>();
      this.language = language;
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public boolean hasNext() {
    try {
      fillSentences();
    } catch (XMLStreamException e) {
      throw new RuntimeException(e);
    }
    return sentences.size() > 0;
  }

  @Override
  public Sentence next() {
    try {
      fillSentences();
      if (sentences.isEmpty()) {
        throw new NoSuchElementException();
      }
      WikipediaSentence wikiSentence = sentences.remove(0);
      String url = getUrl(wikiSentence.title, wikiSentence.revision);
      return new Sentence(wikiSentence.sentence, getSource(), wikiSentence.title, url, wikiSentence.articleCount);
    } catch (XMLStreamException e) {
      throw new RuntimeException(e);
    }
  }

  @NotNull
  private String getUrl(String title, Integer revision) {
    String url = "http://" + language.getShortCode() + ".wikipedia.org/wiki/" + title;
    if (revision != null) {
      url+="/"+revision;
    }
    return url;
  }

  @Override
  public String getSource() {
    return "wikipedia";
  }

  private void fillSentences() throws XMLStreamException {
    String title = null;
    String namespace = null;
    NodeList pageList = doc.getElementsByTagName("page");
    int pageNumber = 0;
    while (sentences.isEmpty() && pageNumber < pageList.getLength()) {
      Node pageElement = pageList.item(pageNumber);
      NodeList pageElementChildNodes = pageElement.getChildNodes();
      for (int i = 0; i < pageElementChildNodes.getLength(); i++) {
        Node pageElementChildNode = pageElementChildNodes.item(i);
        switch(pageElementChildNode.getNodeName()) {
          case "title":
            title = pageElementChildNode.getTextContent();
            articleCount++;
            if (articleCount % 100 == 0) {
              System.out.println("Article: " + articleCount);
            }
            break;
          case "ns":
            namespace = pageElementChildNode.getTextContent();
            break;
          case "revision":
            Integer revisionId = null;
            NodeList revisionElementChildNodes = pageElementChildNode.getChildNodes();
            for (int j = 0; j < revisionElementChildNodes.getLength(); j++) {
              Node revisionElementChildNode = revisionElementChildNodes.item(j);
              switch (revisionElementChildNode.getNodeName()) {
                case "id":
                  revisionId = Integer.parseInt(revisionElementChildNode.getTextContent());
                break;
                case "text":
                  if (revisionId == null) {
                    System.err.println("No revision id found for "+title);
                  }
                  else {
                    handleTextElement(namespace, title, revisionId, revisionElementChildNode.getTextContent(), articleCount);
                  }
                break;
              }
            }
          break;
        }
      }
      pageNumber++;
    }
  }

  private void handleTextElement(String namespace, String title, Integer revisionId, String text, int articleCount) throws XMLStreamException {
    if (ONLY_ARTICLES && !ARTICLE_NAMESPACE.equals(namespace)) {
      namespaceSkipCount++;
    }

    try {
      if (text.trim().toLowerCase().startsWith("#redirect")) {
        redirectSkipCount++;
      }

      HtmlTools.HtmlAnonymizer anonymizer;
      String articleUrl = getUrl(title, null);
      if (anonymizedArticles.containsKey(articleUrl)) {
        anonymizer = anonymizedArticles.get(articleUrl);
      }
      else {
        anonymizer = textFilter.convert(title, text, articleUrl);
        anonymizedArticles.put(articleUrl, anonymizer);
      }

      String textToCheck = anonymizer.getAnonymizedHtml();
      for (String sentence : sentenceTokenizer.tokenize(textToCheck)) {
        if (acceptSentence(sentence)) {
          sentences.add(new WikipediaSentence(sentence, title, revisionId, articleCount));
        }
      }
    } catch (Exception e) {
      System.err.println("Could not extract text, skipping document: " + e + ", full stacktrace follows:");
      e.printStackTrace();
    }
  }

  static class WikipediaSentence {
    final String sentence;
    final String title;
    final Integer revision;
    final int articleCount;
    WikipediaSentence(String sentence, String title, Integer revision, int articleCount) {
      this.sentence = sentence;
      this.title = title;
      this.revision = revision;
      this.articleCount = articleCount;
    }
  }
}
