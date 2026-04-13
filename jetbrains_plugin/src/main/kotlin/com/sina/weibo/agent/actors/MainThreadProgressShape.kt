// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.sina.weibo.agent.actors

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger

/**
 * Minimal progress bridge used to keep extension RPC calls compatible.
 *
 * This is intentionally a no-op implementation for now: it prevents
 * `Unknown actor MainThreadProgress` from breaking extension flows while
 * JetBrains-side progress UI support is implemented incrementally.
 */
interface MainThreadProgressShape : Disposable {
    fun `$startProgress`(handle: Int, options: Map<String, Any?>?, extensionId: String?): Any?
    fun `$progressReport`(handle: Int, message: Any?): Any?
    fun `$progressEnd`(handle: Int): Any?
}

class MainThreadProgress : MainThreadProgressShape {
    private val logger = Logger.getInstance(MainThreadProgress::class.java)

    override fun `$startProgress`(handle: Int, options: Map<String, Any?>?, extensionId: String?): Any? {
        logger.info("[Progress] start handle=$handle extensionId=$extensionId options=$options")
        return null
    }

    override fun `$progressReport`(handle: Int, message: Any?): Any? {
        logger.debug("[Progress] report handle=$handle message=$message")
        return null
    }

    override fun `$progressEnd`(handle: Int): Any? {
        logger.info("[Progress] end handle=$handle")
        return null
    }

    override fun dispose() {
        logger.info("Dispose MainThreadProgress")
    }
}

