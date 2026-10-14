package pe.axiz.initializr.config;

import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Initializr settings ({@code axiz.initializr.*}).
 *
 * @param frameworkVersion axiz version of the generated projects
 * @param githubApi GitHub API base URL (GitHub Enterprise uses its own)
 * @param githubOrganization organization where repositories are created; the user's account when
 *     empty
 * @param maxFiles maximum number of files pushed to a new repository
 */
@ConfigurationProperties("axiz.initializr")
public record InitializrProperties(
    @DefaultValue("0.1.0-SNAPSHOT") String frameworkVersion,
    @DefaultValue("https://api.github.com") URI githubApi,
    @Nullable String githubOrganization,
    @DefaultValue("2000") int maxFiles) {}
