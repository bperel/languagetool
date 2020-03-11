package org.languagetool.dev.wikipedia;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.scribejava.core.builder.ServiceBuilder;
import com.github.scribejava.core.model.*;
import com.github.scribejava.core.oauth.OAuth10aService;

import java.io.IOException;
import java.util.HashMap;
import java.util.Scanner;
import java.util.concurrent.ExecutionException;

public class MediaWikiApi {
  private static final String API_ENDPOINT_TOKENS = "/w/api.php?action=query&format=json&meta=tokens";

  private static String CONSUMER_KEY;
  private static String CONSUMER_SECRET;

  private String apiBaseUrl;
  private static OAuth10aService service;

  private OAuth1AccessToken accessToken;

  public MediaWikiApi(String language) {
    this.apiBaseUrl = "https://" + language + ".wikipedia.org";
  }

  public static void setup(String consumerKey, String consumerSecret) {
    CONSUMER_KEY = consumerKey;
    CONSUMER_SECRET = consumerSecret;

    service = new ServiceBuilder(CONSUMER_KEY)
      .apiSecret(CONSUMER_SECRET)
      .build(new com.github.scribejava.apis.MediaWikiApi(
        "https://fr.wikipedia.org/w/index.php",
        "https://fr.wikipedia.org/wiki/"
      ));
  }

  public void login() throws InterruptedException, ExecutionException, IOException {
    if (CONSUMER_KEY == null || CONSUMER_SECRET == null) {
      throw new RuntimeException("The consumer key and/or the consumer secret is not set!");
    }

    final Scanner in = new Scanner(System.in);

    System.out.println("=== MediaWiki's OAuth Workflow ===");
    System.out.println();

    // Obtain the Request Token
    System.out.println("Fetching the Request Token...");
    final OAuth1RequestToken requestToken = service.getRequestToken();
    System.out.println("Got the Request Token!");
    System.out.println();

    System.out.println("Now go and authorize ScribeJava here:");
    System.out.println(service.getAuthorizationUrl(requestToken));
    System.out.println("And paste the verifier here");
    System.out.print(">>");
    final String oauthVerifier = in.nextLine();
    System.out.println();

    // Trade the Request Token and Verifier for the Access Token
    System.out.println("Trading the Request Token for an Access Token...");
    accessToken = service.getAccessToken(requestToken, oauthVerifier);
    System.out.println("Got the Access Token!");
    System.out.println("(The raw response looks like this: " + accessToken.getRawResponse() + "')");
    System.out.println();
  }

  public void edit() throws IOException, InterruptedException, ExecutionException {
    if (accessToken == null) {
      throw new RuntimeException("No access token!");
    }

    // Now let's go and ask for a protected resource!
    System.out.println("Now we're going to access a protected resource...");
    final OAuthRequest request = new OAuthRequest(Verb.GET, apiBaseUrl + API_ENDPOINT_TOKENS);
    service.signRequest(accessToken, request);
    Response response = service.execute(request);
    System.out.println("Got it! Lets see what we found...");
    System.out.println();

    @SuppressWarnings("unchecked")
    HashMap<String, HashMap<String, HashMap<String, String>>> result = new ObjectMapper().readValue(response.getBody(), HashMap.class);
    String token = result.get("query").get("tokens").get("csrftoken");
    System.out.println("Token : " + token);
  }
}
