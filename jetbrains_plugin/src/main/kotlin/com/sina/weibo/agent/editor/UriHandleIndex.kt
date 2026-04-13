// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.sina.weibo.agent.editor

import java.util.concurrent.CopyOnWriteArrayList

/**
 * URI 到编辑器句柄的线程安全索引，避免在多线程下直接共享 MutableList。
 */
internal class UriHandleIndex {
    private val uriToHandles = java.util.concurrent.ConcurrentHashMap<String, CopyOnWriteArrayList<EditorHolder>>()

    fun add(uriPath: String, handle: EditorHolder) {
        uriToHandles.computeIfAbsent(uriPath) { CopyOnWriteArrayList() }.add(handle)
    }

    fun remove(uriPath: String, handle: EditorHolder) {
        uriToHandles[uriPath]?.let { handles ->
            handles.remove(handle)
            if (handles.isEmpty()) {
                uriToHandles.remove(uriPath, handles)
            }
        }
    }

    fun get(uriPath: String): List<EditorHolder> {
        return uriToHandles[uriPath]?.toList() ?: emptyList()
    }
}
