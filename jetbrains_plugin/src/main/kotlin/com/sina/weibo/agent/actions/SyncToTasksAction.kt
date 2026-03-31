// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.sina.weibo.agent.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.sina.weibo.agent.extensions.plugin.costrict.CostrictFileConstants

/**
 * 同步设计到任务的操作类
 * 继承 WorkflowActionBase，实现同步设计到任务的逻辑
 */
class SyncToTasksAction : WorkflowActionBase(
    actionName = "同步设计到任务",
    rpcCommand = "costrict.coworkflow.syncToTasksJetbrains",
    actionType = "sync_to_tasks"
) {
    
    override fun collectParams(
        project: Project,
        editor: Editor,
        psiFile: PsiFile,
        virtualFile: VirtualFile
    ): WorkflowActionParams {
        // 验证文件类型
        if (virtualFile.name != CostrictFileConstants.DESIGN_FILE) {
            throw IllegalStateException("此操作只能在 design.md 文件中执行")
        }
        
        val document = editor.document
        val caretModel = editor.caretModel
        val lineNumber = document.getLineNumber(caretModel.offset)
        
        // 获取整个 design.md 内容
        val designContent = getFileContent(editor)
        
        // 获取选中文本（用于传递给 VSCode 扩展）
        val selectionModel = editor.selectionModel
        val selectedText = selectionModel.selectedText ?: ""
        
        val startLine = if (selectionModel.hasSelection()) {
            document.getLineNumber(selectionModel.selectionStart)
        } else {
            lineNumber
        }
        
        val endLine = if (selectionModel.hasSelection()) {
            document.getLineNumber(selectionModel.selectionEnd)
        } else {
            lineNumber
        }
        
        return WorkflowActionParams(
            filePath = virtualFile.path,
            documentType = "design",
            lineNumber = lineNumber,
            selectedText = selectedText,
            startLine = startLine,
            endLine = endLine,
            designContent = designContent,
            actionType = "sync_to_tasks"
        )
    }
}
