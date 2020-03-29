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
 * An access token, fetched from a database.
 * @since 4.2
 */
public class AccessToken {

  private Integer id;
  private final String languageCode;
  private final String username;
  private final String accessToken;
  private final String accessTokenSecret;

  public AccessToken(Integer id, String languageCode, String username, String accessToken, String accessTokenSecret) {
    this.id = id;
    this.languageCode = languageCode;
    this.username = username;
    this.accessToken = accessToken;
    this.accessTokenSecret = accessTokenSecret;
  }

  public Integer getId() {
    return id;
  }

  public String getLanguageCode() {
    return languageCode;
  }

  public String getUsername() {
    return username;
  }

  public String getAccessToken() {
    return accessToken;
  }

  public String getAccessTokenSecret() {
    return accessTokenSecret;
  }
}
