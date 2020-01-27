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
  private final Integer version;
  private final String languageCode;
  private final String ruleid;
  private final String ruleCategory;
  private final String ruleSubid;
  private final String ruleDescription;
  private final String message;
  private final String errorContext;
  private final String smallErrorContext;
  private final Date corpusDate;
  private final Date checkDate;
  private final String sourceuri;
  private final String sourceType;
  private final Boolean isVisible;
  private final String replacementSuggestion;

  public CorpusMatchEntry(Integer id, Integer version, String languageCode, String ruleid, String ruleCategory, String ruleSubid, String ruleDescription, String message, String errorContext, String smallErrorContext, Date corpusDate, Date checkDate, String sourceuri, String sourceType, Boolean isVisible, String replacementSuggestion) {
    this.id = id;
    this.version = version;
    this.languageCode = languageCode;
    this.ruleid = ruleid;
    this.ruleCategory = ruleCategory;
    this.ruleSubid = ruleSubid;
    this.ruleDescription = ruleDescription;
    this.message = message;
    this.errorContext = errorContext;
    this.smallErrorContext = smallErrorContext;
    this.corpusDate = corpusDate;
    this.checkDate = checkDate;
    this.sourceuri = sourceuri;
    this.sourceType = sourceType;
    this.isVisible = isVisible;
    this.replacementSuggestion = replacementSuggestion;
  }

  public Integer getVersion() {
    return version;
  }

  public String getLanguageCode() {
    return languageCode;
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

  public Date getCorpusDate() {
    return corpusDate;
  }

  public Date getCheckDate() {
    return checkDate;
  }

  public String getSourceuri() {
    return sourceuri;
  }

  public String getSourceType() {
    return sourceType;
  }

  public Boolean getVisible() {
    return isVisible;
  }

  public String getReplacementSuggestion() {
    return replacementSuggestion;
  }
}
