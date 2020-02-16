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

/**
 * A corpus article, fetched from a database.
 * @since 4.2
 */
public class CorpusArticleEntry {

  private Integer id;
  private final String title;
  private final Integer revision;
  private final String wikitext;
  private final String anonymizedHtml;

  public CorpusArticleEntry(Integer id, String title, Integer revision, String wikiText, String anonymizedHtml) {
    this.id = id;
    this.title = title;
    this.revision = revision;
    this.wikitext = wikiText;
    this.anonymizedHtml = anonymizedHtml;
  }

  public Integer getId() {
    return id;
  }

  public String getTitle() {
    return title;
  }

  public Integer getRevision() {
    return revision;
  }

  public String getAnonymizedHtml() {
    return anonymizedHtml;
  }

  public String getWikitext() {
    return wikitext;
  }
}
