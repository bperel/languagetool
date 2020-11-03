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

import java.sql.Timestamp;

/**
 * A corpus article, fetched from a database.
 * @since 4.2
 */
public class CorpusArticleEntry {

  private Integer id;
  private final String languageCode;
  private final String title;
  private final Integer revision;
  private final String wikitext;
  private final String anonymizedHtml;
  private final String cssUrl;
  private final Boolean analyzed;
  private final Timestamp importDate;
  private final String url;

  public CorpusArticleEntry(Integer id, String languageCode, String title, Integer revision, String wikiText, String anonymizedHtml, String cssUrl, Boolean analyzed, Timestamp importDate, String url) {
    this.id = id;
    this.languageCode = languageCode;
    this.title = title;
    this.revision = revision;
    this.wikitext = wikiText;
    this.anonymizedHtml = anonymizedHtml;
    this.cssUrl = cssUrl;
    this.analyzed = analyzed;
    this.importDate = importDate;
    this.url = url;
  }

  public Integer getId() {
    return id;
  }

  public String getLanguageCode() {
    return languageCode;
  }

  public String getTitle() {
    return title;
  }

  public String getWikitext() {
    return wikitext;
  }

  public Boolean getAnalyzed() {
    return analyzed;
  }

  public String getUrl() {
    return url;
  }

}
