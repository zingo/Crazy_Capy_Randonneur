// Copyright (c) 2026 Crazy Capy Randonneur contributors
// SPDX-License-Identifier: Apache-2.0
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CrazyCapyRouting"

include(":app")