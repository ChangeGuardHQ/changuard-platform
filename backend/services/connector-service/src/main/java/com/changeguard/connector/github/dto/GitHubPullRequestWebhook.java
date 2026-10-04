package com.changeguard.connector.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents the payload sent by GitHub when a pull request event is received.
 * This DTO is used to deserialize webhook events for pull request lifecycle
 * updates.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubPullRequestWebhook(
        String action,
        @JsonProperty("pull_request")
        PullRequest pullRequest,
        Repository repository,
        Installation installation,
        Sender sender
        ) {

    /**
     * Details of the pull request included in the webhook payload.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PullRequest(
            Long id,
            Integer number,
            String title,
            Boolean merged,
            @JsonProperty("merge_commit_sha")
            String mergeCommitSha,
            @JsonProperty("merged_at")
            String mergedAt,
            User user,
            @JsonProperty("merged_by")
            User mergedBy,
            Head head,
            Base base
            ) {

    }

    /**
     * Repository metadata associated with the pull request event.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Repository(
            Long id,
            String name,
            @JsonProperty("full_name")
            String fullName,
            Owner owner
            ) {

    }

    /**
     * Repository owner information as provided by GitHub.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Owner(
            Long id,
            String login
            ) {

    }

    /**
     * User identity for a GitHub actor or assignee represented in the payload.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(
            Long id,
            String login
            ) {

    }

    /**
     * The source branch associated with the pull request.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Head(
            String ref,
            String sha
            ) {

    }

    /**
     * The target branch for the pull request.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Base(
            String ref,
            String sha
            ) {

    }

    /**
     * GitHub App installation information related to the webhook.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Installation(
            Long id
            ) {

    }

    /**
     * The GitHub user that triggered the webhook event.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sender(
            Long id,
            String login
            ) {

    }
}
