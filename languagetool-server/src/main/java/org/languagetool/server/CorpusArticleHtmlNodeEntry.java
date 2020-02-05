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
 * An HTML node from a corpus article, fetched from a database.
 * @since 4.2
 */
public class CorpusArticleHtmlNodeEntry {

  private Integer id;
  private final Integer articleId;
  private final Integer parentId;
  private final Integer childIndex;
  private final String tagName;

  public CorpusArticleHtmlNodeEntry(Integer articleId, Integer parentId, Integer childIndex, String tagName) {
    this.articleId = articleId;
    this.parentId = parentId;
    this.childIndex = childIndex;
    this.tagName = tagName;
  }

  public Integer getArticleId() {
    return articleId;
  }

  public Integer getParentId() {
    return parentId;
  }

  public Integer getChildIndex() {
    return childIndex;
  }

  public String getTagName() {
    return tagName;
  }
}
