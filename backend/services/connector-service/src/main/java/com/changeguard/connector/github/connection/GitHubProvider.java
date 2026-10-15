package com.changeguard.connector.github.connection;

import java.util.List;

/** Provider metadata verified using this service's GitHub App credentials. */
public interface GitHubProvider {
    Installation installation(long installationId);
    List<GitHubRepository> repositories(long installationId);

    record Installation(long installationId, long appId, long accountId,
            String accountLogin, String accountType, boolean suspended) { }

    record GitHubRepository(long repositoryId, long ownerId, String fullName, String defaultBranch) { }
}
