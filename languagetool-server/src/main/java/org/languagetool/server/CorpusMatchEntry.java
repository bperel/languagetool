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

import java.sql.Date;

/**
 * A corpus match, fetched from a database.
 * @since 4.2
 */
public class CorpusMatchEntry {

  private Integer id;
  private final Integer articleId;
  private final String articleLanguageCode;
  private final String ruleid;
  private final String ruleCategory;
  private final String ruleSubid;
  private final String ruleDescription;
  private final String message;
  private final String errorContext;
  private final String smallErrorContext;
  private final String htmlErrorContext;
  private final String replacementSuggestion;
  private final Boolean applied;
  private final Date appliedDate;
  private final String appliedReason;
  private final String languagetoolVersion;

  public CorpusMatchEntry(Integer id, Integer articleId, String articleLanguageCode, String ruleid, String ruleCategory, String ruleSubid, String ruleDescription, String message, String errorContext, String smallErrorContext, String htmlErrorContext, String replacementSuggestion, Boolean applied, Date appliedDate, String appliedReason, String languagetoolVersion) {
    this.id = id;
    this.articleId = articleId;
    this.articleLanguageCode = articleLanguageCode;
    this.ruleid = ruleid;
    this.ruleCategory = ruleCategory;
    this.ruleSubid = ruleSubid;
    this.ruleDescription = ruleDescription;
    this.message = message;
    this.errorContext = errorContext;
    this.smallErrorContext = smallErrorContext;
    this.htmlErrorContext = htmlErrorContext;
    this.replacementSuggestion = replacementSuggestion;
    this.applied = applied;
    this.appliedDate = appliedDate;
    this.appliedReason = appliedReason;
    this.languagetoolVersion = languagetoolVersion;
  }

  public Integer getId() {
    return id;
  }

  public Integer getArticleId() {
    return articleId;
  }

  public String getArticleLanguageCode() {
    return articleLanguageCode;
  }

  public String getRuleid() {
    return ruleid;
  }

  public String getRuleCategory() {
    return ruleCategory;
  }

  public String getRuleSubid() {
    return ruleSubid;
  }

  public String getRuleDescription() {
    return ruleDescription;
  }

  public String getMessage() {
    return message;
  }

  public String getErrorContext() {
    return errorContext;
  }

  public String getSmallErrorContext() {
    return smallErrorContext;
  }

  public String getHtmlErrorContext() {
    return htmlErrorContext;
  }

  public String getReplacementSuggestion() {
    return replacementSuggestion;
  }

  public Boolean getApplied() {
    return applied;
  }

  public Date getAppliedDate() {
    return appliedDate;
  }

  public String getAppliedReason() {
    return appliedReason;
  }

  public String getLanguagetoolVersion() {
    return languagetoolVersion;
  }
}
