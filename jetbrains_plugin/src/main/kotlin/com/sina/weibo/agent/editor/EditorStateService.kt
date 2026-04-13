// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.sina.weibo.agent.editor

import com.intellij.openapi.project.Project
import com.sina.weibo.agent.core.PluginContext
import com.sina.weibo.agent.core.ServiceProxyRegistry
import com.sina.weibo.agent.ipc.proxy.ProxyIdentifier
import com.sina.weibo.agent.ipc.proxy.interfaces.ExtHostDocumentsAndEditorsProxy
import com.sina.weibo.agent.ipc.proxy.interfaces.ExtHostDocumentsProxy
import com.sina.weibo.agent.ipc.proxy.interfaces.ExtHostEditorTabsProxy
import com.sina.weibo.agent.ipc.proxy.interfaces.ExtHostEditorsProxy
import com.sina.weibo.agent.util.URI

class EditorStateService(val project: Project) {
    private fun <T> getProxy(identifier: ProxyIdentifier<T>): T? {
        return PluginContext.getInstance(project).getRPCProtocol()?.getProxy(identifier)
    }

    fun acceptDocumentsAndEditorsDelta(detail: DocumentsAndEditorsDelta) {
        getProxy(ServiceProxyRegistry.ExtHostContext.ExtHostDocumentsAndEditors)
            ?.acceptDocumentsAndEditorsDelta(detail)
    }

    fun acceptEditorPropertiesChanged(detail: Map<String, EditorPropertiesChangeData>) {
        getProxy(ServiceProxyRegistry.ExtHostContext.ExtHostEditors)?.let {
            for ((id, data) in detail) {
                it.acceptEditorPropertiesChanged(id, data)
            }
        }
    }

    fun acceptModelChanged(detail: Map<URI, ModelChangedEvent>) {
        getProxy(ServiceProxyRegistry.ExtHostContext.ExtHostDocuments)?.let {
            for ((uri, data) in detail) {
                it.acceptModelChanged(uri, data, data.isDirty)
            }
        }
    }
}

class TabStateService(val project: Project) {
    private fun getExtHostEditorTabsProxy(): ExtHostEditorTabsProxy? {
        return PluginContext.getInstance(project).getRPCProtocol()
            ?.getProxy(ServiceProxyRegistry.ExtHostContext.ExtHostEditorTabs)
    }

    fun acceptEditorTabModel(detail: List<EditorTabGroupDto>) {
        getExtHostEditorTabsProxy()?.acceptEditorTabModel(detail)
    }

    fun acceptTabOperation(detail: TabOperation) {
        getExtHostEditorTabsProxy()?.acceptTabOperation(detail)
    }

    fun acceptTabGroupUpdate(detail: EditorTabGroupDto) {
        getExtHostEditorTabsProxy()?.acceptTabGroupUpdate(detail)
    }
}
