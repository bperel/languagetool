/* LanguageTool, a natural language style checker
 * Copyright (C) 2011 Daniel Naber (http://www.danielnaber.de)
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
package org.languagetool.dev.wikipedia;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.languagetool.tools.HtmlTools;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.zip.GZIPInputStream;

/**
 * Convert Wikipedia wikicode syntax to HTML using Parsoid.
 */
public class ParsoidWikipediaTextParser {

  private final String urlBase;
  private final String languageCode;

  private static final int TIMEOUT = 60000;

  public ParsoidWikipediaTextParser(String languageCode, String urlBase) {
    this.languageCode = languageCode;
    this.urlBase = urlBase;
  }

  public HtmlTools.HtmlAnonymizer convertWikitextToHtml(String title, String wikiText) throws SocketTimeoutException {
    try {
      String html = convertWikitextToHtml(wikiText);
      if (html == null) {
        return null;
      }
      HtmlTools.HtmlAnonymizer htmlAnonymizer = HtmlTools.HtmlAnonymizer.createFromHtml(title, wikiText, html);
      htmlAnonymizer.anonymize();

      return htmlAnonymizer;
    } catch (SocketTimeoutException e) {
      throw e;
    } catch (IOException | ParserConfigurationException | SAXException e) {
      e.printStackTrace();
      return null;
    }
  }

  private String convertWikitextToHtml(String inputText) throws SocketTimeoutException {
    URL url;
    try {
      url = new URL(this.urlBase + "/wikipedia_" + languageCode + "/v3/transform/wikitext/to/html");
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();

      HashMap<String, Object> params = new HashMap<>();
      params.put("wikitext", inputText);
      String requestBody = new ObjectMapper().writeValueAsString(params);

      conn.setDoInput(true);
      conn.setDoOutput(true);
      conn.setRequestMethod("POST");
      conn.setUseCaches(false);
      conn.setRequestProperty("Accept-Encoding", "gzip");
      conn.setRequestProperty("Content-Type", "application/json");
      conn.setConnectTimeout(TIMEOUT);
      conn.setReadTimeout(TIMEOUT);

      PrintWriter out = new PrintWriter(new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8));
      out.print(requestBody);
      out.close();

      conn.connect();

      InputStreamReader reader;
      if ("gzip".equals(conn.getContentEncoding())) {
        reader = new InputStreamReader(new GZIPInputStream(conn.getInputStream()));
      }
      else {
        reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8);
      }

      BufferedReader bufferedReader = new BufferedReader(reader);
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = bufferedReader.readLine()) != null) {
        sb.append(line).append("\n");
      }
      bufferedReader.close();

      return sb.toString();
    } catch (IOException e) {
      if (e instanceof SocketTimeoutException) {
        System.err.println("Timeout when calling Parsoid");
        throw (SocketTimeoutException) e;
      }
      else {
        e.printStackTrace();
      }
      return null;
    }
  }
}
