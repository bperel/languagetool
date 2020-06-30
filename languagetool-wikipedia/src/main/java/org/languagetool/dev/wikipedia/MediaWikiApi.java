package org.languagetool.dev.wikipedia;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.scribejava.core.builder.ServiceBuilder;
import com.github.scribejava.core.model.*;
import com.github.scribejava.core.oauth.OAuth10aService;

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;

public class MediaWikiApi {
  private static final String API_ENDPOINT_BASE = "/w/api.php";
  private static final String API_ENDPOINT_TOKENS = "/w/api.php?action=query&format=json&meta=tokens";
  private static final String API_ENDPOINT_USERINFO = "/w/api.php?action=query&format=json&meta=userinfo";

  public static final String[] SUPPORTED_LANGUAGES = new String[]{"ca", "de", "en", "fr", "nl", "pl", "pt", "ru", "uk"};

  private static HashMap<String, OAuth10aService> services = new HashMap<>();
  private static HashMap<String, HashMap<String, OAuth1RequestToken>> requestTokens = new HashMap<>();

  private String language;
  private OAuth1AccessToken accessTokenWithSecret;

  public MediaWikiApi(String language) {
    this.language = language;
  }

  public MediaWikiApi(String language, String accessToken, String accessTokenSecret) {
    this.language = language;
    this.accessTokenWithSecret = new OAuth1AccessToken(accessToken, accessTokenSecret);
  }

  public static String getApiEndpointBase(String language) {
    return "https://" + language + ".wikipedia.org";
  }

  public OAuth1AccessToken getAccessTokenWithSecret() {
    return accessTokenWithSecret;
  }

  public static void setup(String consumerKey, String consumerSecret) {
    for (String language : SUPPORTED_LANGUAGES) {
      if (!services.containsKey(language)) {
        services.put(language, new ServiceBuilder(consumerKey)
          .apiSecret(consumerSecret)
          .build(new com.github.scribejava.apis.MediaWikiApi(
            getApiEndpointBase(language) + "/w/index.php",
            getApiEndpointBase(language) + "/wiki/"
          )));
        requestTokens.put(language, new HashMap<>());
      }
    }
  }

  public String authorize() throws InterruptedException, ExecutionException, IOException {
    if (!services.containsKey(language)) {
      throw new RuntimeException("Language " + language + " has not been set up!");
    }

    System.out.println("Fetching the Request Token...");
    final OAuth1RequestToken requestToken = services.get(language).getRequestToken();
    System.out.println("Got the Request Token!");
    System.out.println();

    addRequestToken(requestToken);
    return requestToken.getToken();
  }

  public String getAuthorizationUrl(String requestToken) {
    if (!services.containsKey(language)) {
      throw new RuntimeException("Language " + language + " has not been set up!");
    }

    return services.get(language).getAuthorizationUrl(getRequestToken(requestToken));
  }

  public void login(String requestToken, String oauthVerifier) throws InterruptedException, ExecutionException, IOException {
    System.out.println();

    if (getRequestToken(requestToken) == null) {
      throw new RuntimeException("Can't find request token " + requestToken);
    }

    // Trade the Request Token and Verifier for the Access Token
    System.out.println("Trading the Request Token for an Access Token...");
    this.accessTokenWithSecret = services.get(language).getAccessToken(getRequestToken(requestToken), oauthVerifier);
    System.out.println("Got the Access Token!");
    System.out.println("(The raw response looks like this: " + this.accessTokenWithSecret.getRawResponse() + "')");
    System.out.println();
  }

  public String getPage(String title) throws InterruptedException, ExecutionException, IOException {
    HashMap<String, String> getPageParameters = new HashMap<>();
    getPageParameters.put("action", "parse");
    getPageParameters.put("prop", "wikitext");
    getPageParameters.put("format", "json");
    getPageParameters.put("formatversion", "2");
    getPageParameters.put("page", title);
    Response getPageResponse = callApiWithAccessToken(Verb.GET, API_ENDPOINT_BASE, getPageParameters);

    HashMap<String, HashMap<String, String>> result = new ObjectMapper().readValue(getPageResponse.getBody(), HashMap.class);
    return result.get("parse").get("wikitext");
  }

  public void edit(String title, String content, String editSummary) throws IOException, InterruptedException, ExecutionException {
    Response tokenResponse = callApiWithAccessToken(Verb.GET, API_ENDPOINT_TOKENS, new HashMap<>());

    HashMap<String, HashMap<String, HashMap<String, String>>> result = new ObjectMapper().readValue(tokenResponse.getBody(), HashMap.class);
    String token = result.get("query").get("tokens").get("csrftoken");
    System.out.println("Token : " + token);

    HashMap<String, String> editParameters = new HashMap<>();
    editParameters.put("action", "edit");
    editParameters.put("format", "json");
    editParameters.put("title", title);
    editParameters.put("text", content);
    editParameters.put("summary", editSummary);
    editParameters.put("minor", "true");
    editParameters.put("token", token);
    Response editResponse = callApiWithAccessToken(Verb.POST, API_ENDPOINT_BASE, editParameters);
    System.out.println("Response : " + editResponse.getBody());
  }

  private Response callApiWithAccessToken(Verb verb, String endpoint, HashMap<String, String> parameters) throws InterruptedException, ExecutionException, IOException {
    final OAuthRequest request = new OAuthRequest(verb, getApiEndpointBase(language) + endpoint);
    for (String bodyParameterKey : parameters.keySet()) {
      if (verb.equals(Verb.GET)) {
        request.addQuerystringParameter(bodyParameterKey, parameters.get(bodyParameterKey));
      }
      else {
        request.addBodyParameter(bodyParameterKey, parameters.get(bodyParameterKey));
      }
    }
    services.get(language).signRequest(accessTokenWithSecret, request);
    return services.get(language).execute(request);
  }

  public String getUsernameFromAccessToken() throws InterruptedException, ExecutionException, IOException {
    Response userDataResponse = callApiWithAccessToken(Verb.GET, API_ENDPOINT_USERINFO, new HashMap<>());
    HashMap<String, HashMap<String, HashMap<String, String>>> result = new ObjectMapper().readValue(userDataResponse.getBody(), HashMap.class);
    return result.get("query").get("userinfo").get("name");
  }

  private OAuth1RequestToken getRequestToken(String requestToken) {
    return requestTokens.get(language).get(requestToken);
  }

  private void addRequestToken(OAuth1RequestToken requestToken) {
    requestTokens.get(language).put(requestToken.getToken(), requestToken);
  }
}
