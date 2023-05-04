package com.devonfw.tools.ide.url.updater;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.devonfw.tools.ide.common.OperatingSystem;
import com.devonfw.tools.ide.common.SystemArchitecture;
import com.devonfw.tools.ide.url.model.file.UrlChecksum;
import com.devonfw.tools.ide.url.model.file.UrlDownloadFile;
import com.devonfw.tools.ide.url.model.file.UrlFile;
import com.devonfw.tools.ide.url.model.file.UrlStatusFile;
import com.devonfw.tools.ide.url.model.file.json.StatusJson;
import com.devonfw.tools.ide.url.model.file.json.UrlStatus;
import com.devonfw.tools.ide.url.model.file.json.UrlStatusState;
import com.devonfw.tools.ide.url.model.folder.UrlEdition;
import com.devonfw.tools.ide.url.model.folder.UrlRepository;
import com.devonfw.tools.ide.url.model.folder.UrlTool;
import com.devonfw.tools.ide.url.model.folder.UrlVersion;
import com.devonfw.tools.ide.util.DateTimeUtil;
import com.devonfw.tools.ide.util.HexUtil;
import com.google.common.base.Objects;

/**
 * Abstract base implementation of {@link UrlUpdater}. Contains methods for retrieving response bodies from URLs,
 * updating tool versions, and checking if download URLs work.
 */
public abstract class AbstractUrlUpdater implements UrlUpdater {

  private static final Duration TWO_DAYS = Duration.ofDays(2);

  /** {@link OperatingSystem#WINDOWS}. */
  protected static final OperatingSystem WINDOWS = OperatingSystem.WINDOWS;

  /** {@link OperatingSystem#MAC}. */
  protected static final OperatingSystem MAC = OperatingSystem.MAC;

  /** {@link OperatingSystem#LINUX}. */
  protected static final OperatingSystem LINUX = OperatingSystem.LINUX;

  /** {@link SystemArchitecture#X64}. */
  protected static final SystemArchitecture X64 = SystemArchitecture.X64;

  /** {@link SystemArchitecture#ARM64}. */
  protected static final SystemArchitecture ARM64 = SystemArchitecture.ARM64;

  /** The {@link HttpClient} for HTTP requests. */
  protected final HttpClient client = HttpClient.newBuilder().followRedirects(Redirect.ALWAYS).build();

  private static final Logger logger = LoggerFactory.getLogger(AbstractUrlUpdater.class);

  /**
   * @return the name of the {@link UrlTool tool} handled by this updater.
   */
  protected abstract String getTool();

  /**
   * @return the name of the {@link UrlEdition edition} handled by this updater.
   */
  protected String getEdition() {

    return getTool();
  }

  /**
   * @return the combination of {@link #getTool() tool} and {@link #getEdition() edition} but simplified if both are
   *         equal.
   */
  protected final String getToolWithEdition() {

    String tool = getTool();
    String edition = getEdition();
    if (tool.equals(edition)) {
      return tool;
    }
    return tool + "/" + edition;
  }

  /**
   * Retrieves the response body from a given URL.
   *
   * @param url the URL to retrieve the response body from.
   * @return a string representing the response body.
   * @throws IllegalStateException if the response body could not be retrieved.
   */
  protected String doGetResponseBodyAsString(String url) {

    try {
      HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
      HttpResponse<String> response = this.client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 200) {
        return response.body();
      }
      throw new IllegalStateException("Unexpected response code " + response.statusCode() + ":" + response.body());
    } catch (Exception e) {
      throw new IllegalStateException("Failed to retrieve response body from url: " + url, e);
    }
  }

  /**
   * @param url the URL of the download file.
   * @return the {@link InputStream} of response body.
   */
  protected HttpResponse<InputStream> doGetResponseAsStream(String url) {

    try {
      HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
      return this.client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (Exception e) {
      throw new IllegalStateException("Failed to retrieve response from url: " + url, e);
    }
  }

  /**
   * Updates a tool version with the given arguments (OS independent).
   *
   * @param urlVersion the UrlVersion instance to update.
   * @param downloadUrl the URL of the download for the tool.
   * @return true if the version was successfully updated, false otherwise.
   */
  protected boolean doAddVersion(UrlVersion urlVersion, String downloadUrl) {

    return doAddVersion(urlVersion, downloadUrl, null);
  }

  /**
   * Updates a tool version with the given arguments.
   *
   * @param urlVersion the UrlVersion instance to update.
   * @param downloadUrl the URL of the download for the tool.
   * @param os the operating system type for the tool (can be null).
   * @return true if the version was successfully updated, false otherwise.
   */
  protected boolean doAddVersion(UrlVersion urlVersion, String downloadUrl, OperatingSystem os) {

    return doAddVersion(urlVersion, downloadUrl, os, null);
  }

  /**
   * Updates a tool version with the given arguments.
   *
   * @param urlVersion the UrlVersion instance to update.
   * @param url the URL of the download for the tool.
   * @param os the optional {@link OperatingSystem}.
   * @param architecture the optional {@link SystemArchitecture}.
   * @return {@code true} if the version was successfully updated, {@code false} otherwise.
   */
  protected boolean doAddVersion(UrlVersion urlVersion, String url, OperatingSystem os,
      SystemArchitecture architecture) {

    String version = urlVersion.getName();
    url = url.replace("${version}", version);
    if (os != null) {
      url = url.replace("${os}", os.toString());
    }
    if (architecture != null) {
      url = url.replace("${arch}", architecture.toString());
    }
    url = url.replace("${edition}", getEdition());

    return checkDownloadUrl(url, urlVersion, os, architecture);
  }

  /**
   * @param response the {@link HttpResponse}.
   * @return {@code true} if success, {@code false} otherwise.
   */
  protected boolean isSuccess(HttpResponse<?> response) {

    return response.statusCode() == 200;
  }

  /**
   * @param url the URL of the download to check.
   * @param urlVersion the {@link UrlVersion} where to store the collected information like status and checksum.
   * @return {@code true} if the download was checked successfully, {@code false} otherwise.
   */
  private boolean checkDownloadUrl(String url, UrlVersion urlVersion, OperatingSystem os,
      SystemArchitecture architecture) {

    HttpResponse<InputStream> response = doGetResponseAsStream(url);
    int statusCode = response.statusCode();
    boolean success = isSuccess(response);
    String contentType = response.headers().firstValue("content-type").orElse("undefined");
    String tool = getToolWithEdition();
    String version = urlVersion.getName();
    if (success && contentType.startsWith("text")) {
      logger.error("For tool {} and version {} the download has an invalid content type {} for URL {}", tool, version,
          contentType, url);
      success = false;
    }
    if (success) {
      String checksum = doGenerateChecksum(response, url, version, contentType);
      UrlDownloadFile urlDownloadFile = urlVersion.getOrCreateUrls(os, architecture);
      UrlChecksum urlChecksum = urlVersion.getOrCreateChecksum(urlDownloadFile.getName());
      String oldChecksum = urlChecksum.getChecksum();
      if ((oldChecksum != null) && !Objects.equal(oldChecksum, checksum)) {
        logger.error("For tool {} and version {} the mirror URL {} points to a different checksum {} but expected {}.",
            tool, version, url, checksum, oldChecksum);
        success = false;
      } else {
        urlDownloadFile.addUrl(url);
        urlChecksum.setChecksum(checksum);
      }
    }
    doUpdateStatusJson(success, statusCode, urlVersion, url, false);
    if (success) {
      urlVersion.save();
    }
    return success;
  }

  /**
   * @param response the {@link HttpResponse}.
   * @param url the download URL
   * @param version the {@link UrlVersion version} identifier.
   * @return checksum of input stream as hex string
   */
  private String doGenerateChecksum(HttpResponse<InputStream> response, String url, String version,
      String contentType) {

    try (InputStream inputStream = response.body()) {
      MessageDigest md = MessageDigest.getInstance(UrlChecksum.HASH_ALGORITHM);

      byte[] buffer = new byte[8192];
      int bytesRead;
      long size = 0;
      while ((bytesRead = inputStream.read(buffer)) != -1) {
        md.update(buffer, 0, bytesRead);
        size += bytesRead;
      }
      if (size == 0) {
        throw new IllegalStateException("Download empty for " + url);
      }
      byte[] digestBytes = md.digest();
      String checksum = HexUtil.toHexString(digestBytes);
      logger.info(
          "For tool {} and version {} we received {} bytes with content-type {} and computed SHA256 {} from URL {}",
          getToolWithEdition(), version, Long.valueOf(size), contentType, checksum, url);
      return checksum;
    } catch (IOException e) {
      throw new IllegalStateException("Failed to read body of download " + url, e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("No such hash algorithm " + UrlChecksum.HASH_ALGORITHM, e);
    }
  }

  /**
   * Checks if a download URL works and if the file is available for download.
   *
   * @param url the URL to check.
   * @return a URLRequestResult object representing the success or failure of the URL check.
   */
  protected HttpResponse<?> doCheckDownloadViaHeadRequest(String url) {

    try {
      HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
          .method("HEAD", HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(5)).build();

      return this.client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (Exception e) {
      logger.error("Failed to preform HEAD request of URL {}", url, e);
      return null;
    }
  }

  /**
   * Creates or refreshes the status JSON file for a given UrlVersion instance based on the URLRequestResult of checking
   * if a download URL works.
   *
   * @param success - {@code true} on successful HTTP response, {@code false} otherwise.
   * @param statusCode the HTTP status code of the response.
   * @param urlVersion the UrlVersion instance to create or refresh the status JSON file for.
   * @param url the checked download URL.
   * @param update - {@code true} in case the URL was updated (verification), {@code false} otherwise (version/URL
   *        initially added).
   */
  @SuppressWarnings("null") // Eclipse is too stupid to check this
  private void doUpdateStatusJson(boolean success, int statusCode, UrlVersion urlVersion, String url, boolean update) {

    UrlStatusFile urlStatusFile = null;
    StatusJson statusJson = null;
    UrlStatus status = null;
    UrlStatusState errorStatus = null;
    Instant errorTimestamp = null;
    UrlStatusState successStatus = null;
    Instant successTimestamp = null;
    if (success || update) {
      urlStatusFile = urlVersion.getOrCreateStatus();
      statusJson = urlStatusFile.getStatusJson();
      status = statusJson.getOrCreateUrlStatus(url);
      errorStatus = status.getError();
      if (errorStatus != null) {
        errorTimestamp = errorStatus.getTimestamp();
      }
      successStatus = status.getSuccess();
      if (successStatus != null) {
        successTimestamp = successStatus.getTimestamp();
      }
    }
    Integer code = Integer.valueOf(statusCode);
    String version = urlVersion.getName();
    String tool = getToolWithEdition();
    boolean modified = false;
    if (success) {
      if (errorStatus != null) {
        // we avoid git diff overhead by only updating success timestamp if last check was an error
        if (DateTimeUtil.isAfter(errorTimestamp, successTimestamp)) {
          status.setSuccess(new UrlStatusState());
          modified = true;
        }
      }
      logger.info("For tool {} and version {} the download verification suceeded with status code {} for URL {}.", tool,
          version, code, url);
    } else {
      if (status != null) {
        if (errorStatus == null) {
          modified = true;
        } else {
          if (!Objects.equal(code, errorStatus.getCode())) {
            logger.warn("For tool {} and version {} the error status-code changed from {} to {} for URL {}.", tool,
                version, code, errorStatus.getCode(), code, url);
            modified = true;
          }
          if (!modified) {
            // we avoid git diff overhead by only updating error timestamp if last check was a success
            if (DateTimeUtil.isAfter(successTimestamp, errorTimestamp)) {
              modified = true;
            }
          }
        }
        if (modified) {
          errorStatus = new UrlStatusState();
          errorStatus.setCode(code);
          status.setError(errorStatus);
        }
      }
      logger.warn("For tool {} and version {} the download verification failed with status code {} for URL {}.", tool,
          version, code, url);
    }
    if (modified) {
      urlStatusFile.setStatusJson(statusJson); // hack to set modified (better solution welcome)
    }
  }

  /**
   * Updates the tool's versions in the URL repository.
   *
   * @param urlRepository the URL repository to update
   */
  @Override
  public void update(UrlRepository urlRepository) {

    UrlTool tool = urlRepository.getOrCreateChild(getTool());
    UrlEdition edition = tool.getOrCreateChild(getEdition());
    updateExistingVersions(edition);
    Set<String> versions = getVersions();
    String toolWithEdition = getToolWithEdition();
    logger.info("For tool {} we found the following versions : {}", toolWithEdition, versions);
    for (String version : versions) {
      if (edition.getChild(version) == null) {
        try {
          UrlVersion urlVersion = edition.getOrCreateChild(version);
          addVersion(urlVersion);
          urlVersion.save();
        } catch (Exception e) {
          logger.error("For tool {} we failed to add version {}.", toolWithEdition, version, e);
        }
      }
    }
  }

  /**
   * Update existing versions of the tool in the URL repository.
   *
   * @param edition the URL edition to update
   */
  protected void updateExistingVersions(UrlEdition edition) {

    Set<String> existingVersions = edition.getChildNames();
    for (String version : existingVersions) {
      UrlVersion urlVersion = edition.getChild(version);
      if (urlVersion != null) {
        UrlStatusFile urlStatusFile = urlVersion.getOrCreateStatus();
        StatusJson statusJson = urlStatusFile.getStatusJson();
        if (statusJson.isManual()) {
          logger.info("For tool {} the version {} is set to manual, hence skipping update", getToolWithEdition(),
              version);
        } else {
          updateExistingVersion(version, urlVersion, statusJson, urlStatusFile);
          urlVersion.save();
        }
      }
    }
  }

  private void updateExistingVersion(String version, UrlVersion urlVersion, StatusJson statusJson,
      UrlStatusFile urlStatusFile) {

    boolean modified = false;
    String toolWithEdition = getToolWithEdition();
    Instant now = Instant.now();
    for (UrlFile child : urlVersion.getChildren()) {
      if (child instanceof UrlDownloadFile) {
        Set<String> urls = ((UrlDownloadFile) child).getUrls();
        for (String url : urls) {
          if (shouldVerifyDownloadUrl(version, statusJson, toolWithEdition, now)) {
            HttpResponse<?> response = doCheckDownloadViaHeadRequest(url);
            doUpdateStatusJson(isSuccess(response), response.statusCode(), urlVersion, url, true);
            modified = true;
          }
        }
      }
    }
    if (modified) {
      urlStatusFile.save();
    }
  }

  private boolean shouldVerifyDownloadUrl(String url, StatusJson statusJson, String toolWithEdition, Instant now) {

    UrlStatus urlStatus = statusJson.getOrCreateUrlStatus(url);
    UrlStatusState success = urlStatus.getSuccess();
    if (success != null) {
      Instant timestamp = success.getTimestamp();
      Integer delta = DateTimeUtil.compareDuration(timestamp, now, TWO_DAYS);
      if ((delta != null) && (delta.intValue() <= 0)) {
        logger.debug("For tool {} the URL {} has already been checked recently on {}", toolWithEdition, url, timestamp);
        return false;
      }
    }
    return true;
  }

  /**
   * Finds all currently available versions of the {@link UrlEdition tool edition}.
   *
   * @return the {@link Set} with all current versions.
   */
  protected abstract Set<String> getVersions();

  /**
   * @param version the original version (e.g. "v1.0").
   * @return the transformed version (e.g. "1.0") or {@code null} to filter and omit the given version.
   */
  protected String mapVersion(String version) {

    String prefix = getVersionPrefixToRemove();
    if ((prefix != null) && version.startsWith(prefix)) {
      version = version.substring(prefix.length());
    }
    String vLower = version.toLowerCase(Locale.ROOT);
    if (vLower.contains("alpha") || vLower.contains("beta") || vLower.contains("dev") || vLower.contains("snapshot")
        || vLower.contains("preview") || vLower.contains("test") || vLower.contains("tech-preview") //
        || vLower.contains("-pre") || vLower.startsWith("ce-")
        // vscode nonsense
        || vLower.startsWith("bad") || vLower.contains("vsda-") || vLower.contains("translation/")
        || vLower.contains("-insiders")) {
      return null;
    }
    return version;
  }

  /**
   * @return the optional version prefix that has to be removed (e.g. "v").
   */
  protected String getVersionPrefixToRemove() {

    return null;
  }

  /**
   * @param version the version to add (e.g. "1.0").
   * @param versions the {@link Collection} with the versions to collect.
   */
  protected final void addVersion(String version, Collection<String> versions) {

    String mappedVersion = mapVersion(version);
    if ((mappedVersion == null) || mappedVersion.isBlank()) {
      logger.debug("Filtered version {}", version);
      return;
    } else if (!mappedVersion.equals(version)) {
      logger.debug("Mapped version {} to {}", version, mappedVersion);
    }
    boolean added = versions.add(mappedVersion);
    if (!added) {
      logger.warn("Duplicate version {}", version);
    }
  }

  /**
   * Updates the version of a given URL version.
   *
   * @param urlVersion the URL version to be updated
   */
  protected abstract void addVersion(UrlVersion urlVersion);

}