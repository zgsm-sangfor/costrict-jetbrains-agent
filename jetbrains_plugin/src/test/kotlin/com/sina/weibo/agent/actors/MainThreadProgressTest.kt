// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.sina.weibo.agent.actors

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MainThreadProgressTest : BasePlatformTestCase() {
    fun testProgressActorMethodsAreCallable() {
        val actor = MainThreadProgress()

        actor.`$startProgress`(1, mapOf("title" to "test"), "ext.test")
        actor.`$progressReport`(1, mapOf("message" to "working"))
        actor.`$progressEnd`(1)

        actor.dispose()
    }
}
