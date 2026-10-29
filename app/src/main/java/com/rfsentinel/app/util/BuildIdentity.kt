package com.rfsentinel.app.util

import com.rfsentinel.app.BuildConfig

/**
 * Which build this is, as people quote it in a report: "2.31.0 · 83a431d". Two APKs with the same version
 * can differ (a fork, a test build), so the commit is what tells them apart. Network User-Agents keep the
 * plain version: servers have no use for the commit.
 */
object BuildIdentity {
    val label: String get() = label(BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA)

    /** [version], then the commit when the build knew it ("+" after it: built with uncommitted changes). */
    fun label(version: String, sha: String): String = if (sha.isBlank()) version else "$version · $sha"
}
