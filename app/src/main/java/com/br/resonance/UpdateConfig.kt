package com.br.resonance

/**
 * UpdateConfig: Central configuration for GitHub-based update checks in Resonance.
 * Configure repository owner and name centrally without any hardcoded scattered values.
 */
object UpdateConfig {

    /**
     * GitHub repository owner (username or organization).
     * Set to the repository owner where Resonance is hosted.
     */
    var GITHUB_OWNER: String = "1ambharath"

    /**
     * GitHub repository name.
     */
    var GITHUB_REPO: String = "resonance"

    /**
     * Cache duration for update checks (default: 6 hours).
     * Prevents excessive requests and respects GitHub's unauthenticated API rate limits.
     */
    const val CACHE_EXPIRATION_MS: Long = 6 * 60 * 60 * 1000L

    /**
     * Public GitHub Releases API endpoint for latest published stable release.
     * Note: GitHub's /releases/latest endpoint automatically filters out drafts and prereleases.
     */
    fun getLatestReleaseApiUrl(): String {
        return "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
    }

    /**
     * Public GitHub repository releases web URL.
     */
    fun getWebReleasesUrl(): String {
        return "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases"
    }

    /**
     * Public GitHub repository project page.
     */
    fun getRepoWebUrl(): String {
        return "https://github.com/$GITHUB_OWNER/$GITHUB_REPO"
    }
}
