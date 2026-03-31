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
 * 运行所有任务的操作类
 * 继承 WorkflowActionBase，实现运行所有任务的逻辑
 */
class RunAllTasksAction : WorkflowActionBase(
    actionName = "运行所有任务",
    rpcCommand = "costrict.coworkflow.runAllTasksJetbrains",
    actionType = "run_all"
) {
    
    override fun collectParams(
        project: Project,
        editor: Editor,
        psiFile: PsiFile,
        virtualFile: VirtualFile
    ): WorkflowActionParams {
        // 验证文件类型
        if (virtualFile.name != CostrictFileConstants.TASKS_FILE) {
            throw IllegalStateException("此操作只能在 tasks.md 文件中执行")
        }
        
        val document = editor.document
        val caretModel = editor.caretModel
        val lineNumber = document.getLineNumber(caretModel.offset)+1
        
        // 获取整个 tasks.md 内容
        val allTasksContent = getFileContent(editor)
        
        // 获取选中文本（用于传递给 VSCode 扩展）
        val selectionModel = editor.selectionModel
        val selectedText = if (selectionModel.hasSelection()) {
            selectionModel.selectedText
        } else {
            allTasksContent
        }
        
        val startLine = if (selectionModel.hasSelection()) {
            document.getLineNumber(selectionModel.selectionStart) +1 
        } else {
            lineNumber
        }
        
        val endLine = if (selectionModel.hasSelection()) {
            document.getLineNumber(selectionModel.selectionEnd) +1 
        } else {
            lineNumber
        }
        
        return WorkflowActionParams(
            filePath = virtualFile.path,
            documentType = "tasks",
            lineNumber = lineNumber,
            selectedText = selectedText,
            startLine = startLine,
            endLine = endLine,
            allTasksContent = allTasksContent,
            actionType = "run_all"
        )
    }
}
