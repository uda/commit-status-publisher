package jetbrains.buildServer.swarm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.TimeUnit;
import jetbrains.buildServer.commitPublisher.DefaultHttpResponseProcessor;
import jetbrains.buildServer.commitPublisher.HttpPublisherException;
import jetbrains.buildServer.commitPublisher.LoggerUtil;
import jetbrains.buildServer.commitPublisher.PublisherException;
import jetbrains.buildServer.log.LogInitializer;
import jetbrains.buildServer.serverSide.RelativeWebLinks;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.util.Dates;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.util.http.HttpMethod;
import jetbrains.buildServer.vcshostings.http.HttpHelper;
import jetbrains.buildServer.vcshostings.http.HttpResponseProcessor;
import jetbrains.buildServer.vcshostings.http.credentials.HttpCredentials;
import jetbrains.buildServer.vcshostings.http.credentials.UsernamePasswordCredentials;
import org.apache.http.entity.ContentType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static jetbrains.buildServer.swarm.commitPublisher.SwarmPublisherSettings.*;

/**
 * @author kir
 */
public class SwarmClient {

  private final String mySwarmUrl;
  private final String myUsername;
  private final String myTicket;
  private final boolean myAdminRequired;
  private int myConnectionTimeout;
  private final KeyStore myTrustStore;
  private final RelativeWebLinks myWebLinks;

  private final Cache<String, ReviewLoadResponse> myChangelist2ReviewsCache;

  public SwarmClient(@NotNull RelativeWebLinks webLinks, @NotNull Map<String, String> params, int connectionTimeout, @Nullable KeyStore trustStore) {
    myWebLinks = webLinks;
    myUsername = params.get(PARAM_USERNAME);
    myTicket = params.get(PARAM_PASSWORD);
    mySwarmUrl = StringUtil.removeTailingSlash(params.get(PARAM_URL));
    myAdminRequired = StringUtil.isTrue(params.get(PARAM_CREATE_SWARM_TEST));

    myConnectionTimeout = connectionTimeout;
    myTrustStore = trustStore;

    myChangelist2ReviewsCache = Caffeine.newBuilder()
                                        .executor(Runnable::run)
                                        .maximumSize(1000)
                                        .expireAfterWrite(1, TimeUnit.DAYS)
                                        .build();
  }

  public void setConnectionTimeout(int connectionTimeout) {
    myConnectionTimeout = connectionTimeout;
  }

  public void testConnection() throws PublisherException {
    String url = mySwarmUrl + "/api/v9/session";
    try {
      // To ensure ticket was provided, not password:
      HttpHelper.get(url, getCredentials(), null, 5000, myTrustStore, new DefaultHttpResponseProcessor());

      url = mySwarmUrl + "/api/v9/login";
      final String data = "username=" + StringUtil.encodeURLParameter(myUsername) + "&password=" + StringUtil.encodeURLParameter(myTicket);
      // Need to do actual login to read isAdmin flag for the user
      HttpHelper.post(url, null,
                      data, ContentType.APPLICATION_FORM_URLENCODED, null, 5000, myTrustStore, createLoginProcessor());

    } catch (IOException e) {
      throw new PublisherException("Test connection failed for " + url, e);
    }
  }

  @NotNull
  private DefaultHttpResponseProcessor createLoginProcessor() {
    return new DefaultHttpResponseProcessor() {
      @Override
      public void processResponse(HttpHelper.HttpResponse response) throws HttpPublisherException, IOException {
        super.processResponse(response);
        
        if (myAdminRequired) {
          final JsonNode jsonResponse = new ObjectMapper().readTree(response.getContent());
          if (jsonResponse == null) {
            throw new HttpPublisherException("Could not parse Swarm server response as JSON: " + response.getContent());
          }

          JsonNode user = jsonResponse.get("user");
          if (user == null) {
            throw new HttpPublisherException("Could not find user data in Swarm server response: " + response.getContent());
          }

          boolean userIsAdmin = user.get("isAdmin") != null && user.get("isAdmin").asBoolean();
          if (!userIsAdmin) {
            throw new HttpPublisherException("Provided credentials lack admin permissions, which are required to create Swarm test runs.");
          }
        }
      }
    };
  }

  @NotNull
  public List<Long> getOpenReviewIds(@NotNull String changelistId, @NotNull String debugInfo) throws PublisherException {
    ReviewLoadResponse reviews = getReviews(changelistId, debugInfo, false);
    Date expireTime = new Date(System.currentTimeMillis() - Dates.seconds(10));
    if (reviews.getCreated().before(expireTime)) {
      // Reload data, we need fresh info for this call as it is used in publishing build statuses
      reviews = getReviews(changelistId, debugInfo, true);
    }

    if (reviews.getError() != null) {
      try {
        throw reviews.getError();
      } catch (RuntimeException|PublisherException e) {
        throw e;
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    return reviews.getOpenReviewIds();
  }

  @NotNull
  public ReviewLoadResponse getReviews(@NotNull String changelistId, @NotNull String debugInfo, boolean forceLoad) {
    if (forceLoad) {
      myChangelist2ReviewsCache.invalidate(changelistId);
    }

    return Objects.requireNonNull(myChangelist2ReviewsCache.get(changelistId, (id) -> {
      return loadReviews(changelistId, debugInfo);
    }));
  }

  @Nullable
  public ReviewLoadResponse getCachedReviews(@NotNull String changelistId) {
    return myChangelist2ReviewsCache.getIfPresent(changelistId);
  }

  @NotNull
  @VisibleForTesting
  protected ReviewLoadResponse loadReviews(@NotNull String changelistId, @NotNull String debugInfo) {
    String getReviewsUrl = mySwarmUrl + "/api/v9/reviews?fields=id,state,stateLabel&change[]=" + changelistId;
    try {
      final ReadReviewsProcessor processor = new ReadReviewsProcessor(debugInfo);
      HttpHelper.get(getReviewsUrl, getCredentials(), null, myConnectionTimeout, myTrustStore, processor);

      return new ReviewLoadResponse(processor.getReviews());
    } catch (IOException|HttpPublisherException e) {
      return new ReviewLoadResponse(new PublisherException("Cannot get list of reviews from " + getReviewsUrl + " for " + debugInfo + ": " + e, e));
    }
  }

  public void addCommentToReview(@NotNull Long reviewId, @NotNull String fullComment, @NotNull String debugInfo, boolean silenceNotification) throws PublisherException {

    final String addCommentUrl = mySwarmUrl + "/api/v9/comments";

    String data = "topic=reviews/" + reviewId + "&body=" + StringUtil.encodeURLParameter(fullComment);
    if (silenceNotification) {
      data += "&silenceNotification=true";
    }

    try {
      HttpHelper.post(addCommentUrl, getCredentials(),
                      data, ContentType.APPLICATION_FORM_URLENCODED, null, myConnectionTimeout, myTrustStore, new DefaultHttpResponseProcessor());
    } catch (IOException e) {
      throw new PublisherException("Cannot add a comment for review at " + addCommentUrl + " for " + debugInfo + ": " + e, e);
    }
  }


  // https://www.perforce.com/manuals/swarm/Content/Swarm/swarm-apidoc_endpoint_integration_tests.html#Create_a__testrun_for_a_review_version
  public void createSwarmTestRun(long reviewId, @NotNull SBuild build, @NotNull String debugBuildInfo) throws PublisherException {
    final String createTestRunUrl = mySwarmUrl + "/api/v10/reviews/" + reviewId + "/testruns";

    try {
      HttpHelper.post(createTestRunUrl, getCredentials(),
                      createTestRunJson(reviewId, build),
                      ContentType.APPLICATION_JSON, null, myConnectionTimeout, myTrustStore, new DefaultHttpResponseProcessor());
    } catch (IOException e) {
      throw new PublisherException("Cannot create test run at " + createTestRunUrl + " for " + debugBuildInfo + ": " + e, e);
    }
  }

  private String createTestRunJson(long reviewId, @NotNull SBuild build) {

    return String.format("{\n" +
                         "  \"change\": %d,\n" +
                         "  \"version\": 1,\n" +
                         "  \"test\": \"%s\",\n" +
                         "  \"startTime\": %d,\n" +
                         "  \"status\": \"running\",\n" +
                         "  \"url\": \"" + myWebLinks.getViewResultsUrl(build) + "\"\n" +
                         "}",
                         reviewId,
                         testNameFrom(build),
                         build.getServerStartDate().getTime());
  }

  @NotNull
  private static String testNameFrom(SBuild build) {
    return StringUtil.truncateStringValueWithDotsAtCenter(build.getBuildTypeExternalId(), 32)
                     .replace('.', '_');
  }

  public void updateSwarmTestRuns(long reviewId, @NotNull SBuild build, @NotNull String debugBuildInfo) throws PublisherException {
    for (Long testRunId : collectTestRunIds(reviewId, build, debugBuildInfo)) {
      changeTestRunStatus(reviewId, testRunId, build, debugBuildInfo);
    }
  }

  @NotNull
  private List<Long> collectTestRunIds(long reviewId, @NotNull SBuild build, @NotNull String debugBuildInfo) throws PublisherException {
    final String testRunUrl = mySwarmUrl + "/api/v10/reviews/" + reviewId + "/testruns";

    final GetRunningTestRuns processor = new GetRunningTestRuns(testNameFrom(build), debugBuildInfo);
    try {
      HttpHelper.get(testRunUrl, getCredentials(), null, myConnectionTimeout, myTrustStore, processor);
    } catch (IOException e) {
      throw new PublisherException("Cannot get test run list at " + testRunUrl + " for " + debugBuildInfo + ": " + e, e);
    }
    return processor.getTestRunIds();
  }

  // https://www.perforce.com/manuals/swarm/Content/Swarm/swarm-apidoc_endpoint_integration_tests.html#Update_details_for_a_testrun_-_PATCH
  private void changeTestRunStatus(long reviewId,
                                   @NotNull Long testRunId,
                                   @NotNull SBuild build,
                                   @NotNull String debugBuildInfo)
    throws PublisherException {
    final String patchTestRunUrl = mySwarmUrl + "/api/v10/reviews/" + reviewId + "/testruns/" + testRunId;

    try {
      // Because PATCH is not supported by ServerBootstrap
      HttpMethod patch = LogInitializer.isUnitTest() ? HttpMethod.POST : HttpMethod.PATCH;

      HttpHelper.http(patch, patchTestRunUrl, getCredentials(),
                      buildJsonForUpdate(build),
                      ContentType.APPLICATION_JSON, null, myConnectionTimeout, myTrustStore, new DefaultHttpResponseProcessor());
    } catch (IOException e) {
      throw new PublisherException("Cannot update test run at " + patchTestRunUrl + " for " + debugBuildInfo + ": " + e, e);
    }
  }

  private static String buildJsonForUpdate(@NotNull SBuild build) {
    final Long completedTime = build.getFinishDate() != null ? build.getFinishDate().getTime() : null;
    final String status = completedTime == null ? "running" : build.getBuildStatus().isSuccessful() ? "pass" : "fail";

    final HashMap<String, Object> data = new HashMap<String, Object>();
    data.put("status", status);
    if (completedTime != null) {
      data.put("completedTime", completedTime.toString());
    }
    data.put("messages", Collections.singletonList(build.getStatusDescriptor().getText()));
    try {
      return new ObjectMapper().writeValueAsString(data);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  private void info(String message) {
    LoggerUtil.LOG.info(message);
  }

  private void debug(String message) {
    LoggerUtil.LOG.info(message);
  }

  @Nullable
  private HttpCredentials getCredentials() {
    return  (myUsername != null && myTicket != null) ? new UsernamePasswordCredentials(myUsername, myTicket) : null;
  }

  /**
   * @return Swarm server URL without tailing slash
   */
  public String getSwarmServerUrl() {
    return mySwarmUrl;
  }

  private class ReadReviewsProcessor implements HttpResponseProcessor<HttpPublisherException> {

    private final List<SingleReview> myReviews = new ArrayList<>();
    private final String myDebugInfo;

    private ReadReviewsProcessor(@NotNull String debugInfo) {
      myDebugInfo = debugInfo;
    }

    @Override
    public void processResponse(HttpHelper.HttpResponse response) throws HttpPublisherException, IOException {
      if (response.getStatusCode() >= 400) {
        if (response.getStatusCode() == 401 || response.getStatusCode() == 403) {
          throw new HttpPublisherException(response.getStatusCode(), response.getStatusText(),
                                           "Cannot access Perforce Swarm Server at '" + mySwarmUrl + "' to add details for " + myDebugInfo);
        }
        throw new HttpPublisherException(response.getStatusCode(), response.getStatusText(), "Cannot get list of related reviews for " + myDebugInfo);
      }

      // response = {"lastSeen":19,"reviews":[{"id":19}],"totalCount":1}
      debug("Reviews response for " + myDebugInfo + " = " + response.getContent() + " " + response.getStatusCode() + " " + response.getStatusText());

      try {
        final JsonNode jsonNode = new ObjectMapper().readTree(response.getContent());
        final ArrayNode reviews = (ArrayNode)jsonNode.get("reviews");

        if (reviews != null) {
          for (Iterator<JsonNode> it = reviews.elements(); it.hasNext(); ) {
            JsonNode element = it.next();
            long id = element.get("id").longValue();
            String state = element.get("state").asText();
            myReviews.add(new SingleReview(id, state));
          }
        }
        if (myReviews.size() > 0) {
          info(String.format("Found Perforce Swarm reviews %s for %s", myReviews, myDebugInfo));
        }

      } catch (JsonProcessingException e) {
        throw new HttpPublisherException("Error parsing JSON response from Perforce Swarm: " + e.getMessage(), e);
      }
    }

    @NotNull
    public List<SingleReview> getReviews() {
      return myReviews;
    }
  }

  private class GetRunningTestRuns implements HttpResponseProcessor<HttpPublisherException> {

    private final List<Long> myTestRunIds = new ArrayList<>();
    private final String myDebugInfo;
    private final String myExpectedTestName;

    private GetRunningTestRuns(@NotNull String expectedTestName, @NotNull String debugInfo) {
      myExpectedTestName = expectedTestName;
      myDebugInfo = debugInfo;
    }

    @Override
    public void processResponse(HttpHelper.HttpResponse response) throws HttpPublisherException, IOException {
      if (response.getStatusCode() >= 400) {
        if (response.getStatusCode() == 401 || response.getStatusCode() == 403) {
          throw new HttpPublisherException(response.getStatusCode(), response.getStatusText(),
                                           "Cannot access Perforce Swarm Server at '" + mySwarmUrl + "' to add details for " + myDebugInfo);
        }
        throw new HttpPublisherException(response.getStatusCode(), response.getStatusText(), "Cannot get the list of review testruns for " + myDebugInfo);
      }

      /*
      {
  "error": null,
  "messages": [],
  "data" : {
    "testruns" : [
      {
        "id": 706,
        "change": 12345,
        "version": 2,
        "test": "global1",
        "startTime": 1567895432,
        "completedTime": 1567895562,
        "status": "pass",
        "messages": [
          "Test completed successfully",
          "another message"
        ],
        "url": "http://my.jenkins.com/projectx/main/1224"
        "uuid": "FAE4501C-E4BC-73E4-A11A-FF710601BC3F"
      },
      {
        <ids for other testruns of the review, formatted as above>
      }
    ]
  }
}
       */

      debug("Test runs response for " + myDebugInfo + " = " + response.getContent() + " " + response.getStatusCode() + " " + response.getStatusText());

      try {
        final JsonNode jsonNode = new ObjectMapper().readTree(response.getContent());
        final JsonNode dataNode = jsonNode.get("data");
        if (dataNode == null) {
          return;
        }

        final ArrayNode testruns = (ArrayNode)dataNode.get("testruns");

        if (testruns != null) {
          for (Iterator<JsonNode> it = testruns.elements(); it.hasNext(); ) {
            JsonNode element = it.next();
            // Collect test runs whose test name match external build configuration ID and which are not completed
            final JsonNode completedTime = element.get("completedTime");
            final JsonNode test = element.get("test");
            if (test != null && myExpectedTestName.equals(test.textValue()) && (completedTime == null || completedTime.isNull())) {
              myTestRunIds.add(element.get("id").longValue());
            }
          }
        }
        if (myTestRunIds.size() > 0) {
          info(String.format("Found Perforce Swarm testruns %s for %s", myTestRunIds, myDebugInfo));
        }

      } catch (JsonProcessingException e) {
        throw new HttpPublisherException("Error parsing JSON response from Perforce Swarm: " + e.getMessage(), e);
      }
    }

    @NotNull
    public List<Long> getTestRunIds() {
      return myTestRunIds;
    }
  }

}
