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

  private static HashMap<String, OAuth10aService> services = new HashMap<>();
  private static HashMap<String, OAuth1RequestToken> requestTokens = new HashMap<>();
  private static HashMap<String, OAuth1AccessToken> accessTokens = new HashMap<>();

  private String language;

  public MediaWikiApi(String language) {
    this.language = language;
  }

  public static String getApiEndpointBase(String language) {
    return "https://" + language + ".wikipedia.org";
  }

  public static void setup(String consumerKey, String consumerSecret, String[] languages) {
    for (String language : languages) {
      if (!services.containsKey(language)) {
        services.put(language, new ServiceBuilder(consumerKey)
          .apiSecret(consumerSecret)
          .build(new com.github.scribejava.apis.MediaWikiApi(
            getApiEndpointBase(language) + "/w/index.php",
            getApiEndpointBase(language) + "/wiki/"
          )));
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

    requestTokens.put(requestToken.getToken(), requestToken);

    return requestToken.getToken();
  }

  public String getAuthorizationUrl(String requestToken) {
    if (!services.containsKey(language)) {
      throw new RuntimeException("Language " + language + " has not been set up!");
    }

    return services.get(language).getAuthorizationUrl(requestTokens.get(requestToken));
  }

  public String login(String requestToken, String oauthVerifier) throws InterruptedException, ExecutionException, IOException {
    System.out.println();

    if (!requestTokens.containsKey(requestToken)) {
      throw new RuntimeException("Can't find request token " + requestToken);
    }

    // Trade the Request Token and Verifier for the Access Token
    System.out.println("Trading the Request Token for an Access Token...");
    OAuth1AccessToken accessToken = services.get(language).getAccessToken(requestTokens.get(requestToken), oauthVerifier);
    accessTokens.put(accessToken.getToken(), accessToken);
    System.out.println("Got the Access Token!");
    System.out.println("(The raw response looks like this: " + accessToken.getRawResponse() + "')");
    System.out.println();

    return accessToken.getToken();
  }

  public String getPage(String accessToken, String title) throws InterruptedException, ExecutionException, IOException {
    HashMap<String, String> getPageParameters = new HashMap<>();
    getPageParameters.put("action", "parse");
    getPageParameters.put("prop", "wikitext");
    getPageParameters.put("format", "json");
    getPageParameters.put("formatversion", "2");
    getPageParameters.put("page", title);
    Response getPageResponse = callApiWithAccessToken(accessToken, Verb.GET, API_ENDPOINT_BASE, getPageParameters);

    HashMap<String, HashMap<String, String>> result = new ObjectMapper().readValue(getPageResponse.getBody(), HashMap.class);
    return result.get("parse").get("wikitext");
  }

  public void edit(String accessToken, String title, String content) throws IOException, InterruptedException, ExecutionException {
    Response tokenResponse = callApiWithAccessToken(accessToken, Verb.GET, API_ENDPOINT_TOKENS, new HashMap<>());

    HashMap<String, HashMap<String, HashMap<String, String>>> result = new ObjectMapper().readValue(tokenResponse.getBody(), HashMap.class);
    String token = result.get("query").get("tokens").get("csrftoken");
    System.out.println("Token : " + token);

    HashMap<String, String> editParameters = new HashMap<>();
    editParameters.put("action", "edit");
    editParameters.put("format", "json");
    editParameters.put("title", title);
    editParameters.put("text", content);
    editParameters.put("token", token);
    Response editResponse = callApiWithAccessToken(accessToken, Verb.POST, API_ENDPOINT_BASE, editParameters);
    System.out.println("Response : " + editResponse.getBody());
  }

  public String getUserName(String accessToken) throws InterruptedException, ExecutionException, IOException {
    Response response = callApiWithAccessToken(accessToken, Verb.GET, API_ENDPOINT_USERINFO, new HashMap<>());

    @SuppressWarnings("unchecked")
    HashMap<String, HashMap<String, HashMap<String, String>>> result = new ObjectMapper().readValue(response.getBody(), HashMap.class);
    return result.get("query").get("userinfo").get("name");
  }

  private Response callApiWithAccessToken(String accessToken, Verb verb, String endpoint, HashMap<String, String> parameters) throws InterruptedException, ExecutionException, IOException {
    if (! accessTokens.containsKey(accessToken)) {
      throw new RuntimeException("Can't find access token " + accessToken);
    }

    final OAuthRequest request = new OAuthRequest(verb, getApiEndpointBase(language) + endpoint);
    for (String bodyParameterKey : parameters.keySet()) {
      if (verb.equals(Verb.GET)) {
        request.addQuerystringParameter(bodyParameterKey, parameters.get(bodyParameterKey));
      }
      else {
        request.addBodyParameter(bodyParameterKey, parameters.get(bodyParameterKey));
      }
    }
    services.get(language).signRequest(accessTokens.get(accessToken), request);
    return services.get(language).execute(request);
  }
}
