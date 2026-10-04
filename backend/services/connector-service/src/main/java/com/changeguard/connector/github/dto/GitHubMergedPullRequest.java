package com.changeguard.connector.github.dto;

import java.time.Instant;

/**
 * Represents normalized merge details from a verified GitHub webhook for event
 * construction. This immutable DTO carries the delivery metadata and the main
 * merge context needed to correlate the merge with the relevant repository,
 * branches, and actor.
 */
public record GitHubMergedPullRequest(
        /**
         * Unique identifier for the GitHub delivery event.
         */
        String deliveryId,
        /**
         * GitHub repository identifier associated with the pull request.
         */
        String repositoryId,
        /**
         * Full repository name in the form {@code owner/repo}.
         */
        String repositoryFullName,
        /**
         * Pull request number within the repository.
         */
        Integer pullRequestNumber,
        /**
         * Title of the merged pull request.
         */
        String pullRequestTitle,
        /**
         * Commit SHA for the merged change.
         */
        String commitSha,
        /**
         * Source branch from which the pull request originated.
         */
        String sourceBranch,
        /**
         * Target branch into which the pull request was merged.
         */
        String targetBranch,
        /**
         * GitHub username of the user who initiated or triggered the merge
         * event.
         */
        String actorLogin,
        /**
         * Provider timestamp at which the pull request was merged.
         */
        Instant mergedAt
        ) {

}
