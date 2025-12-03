// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.sina.weibo.agent.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.editor.Document

/**
 * Coworkflow命令转换工具类
 * 负责将IntelliJ插件的参数转换为VSCode插件的COWORKFLOW_COMMANDS命令参数
 */
object CoworkflowCommandConverter {
    
    /**
     * VSCode插件命令ID常量
     */
    object VSCodeCommands {
        const val UPDATE_SECTION = "zgsm.coworkflow.updateSection"
        const val RUN_TASK = "zgsm.coworkflow.runTask"
        const val RUN_ALL_TASKS = "zgsm.coworkflow.runAllTasks"
        const val RETRY_TASK = "zgsm.coworkflow.retryTask"
        const val REFRESH_CODELENS = "zgsm.coworkflow.refreshCodeLens"
        const val REFRESH_DECORATIONS = "zgsm.coworkflow.refreshDecorations"
        const val RUN_TEST = "zgsm.coworkflow.runTest"
    }
    
    /**
     * 文档类型枚举
     */
    enum class DocumentType(val value: String) {
        REQUIREMENTS("requirements"),
        DESIGN("design"),
        TASKS("tasks")
    }
    
    /**
     * 动作类型枚举
     */
    enum class ActionType(val value: String) {
        UPDATE("update"),
        RUN("run"),
        RETRY("retry"),
        RUN_ALL("run_all"),
        RUN_TEST("run_test")
    }
    
    /**
     * 创建CoworkflowCodeLens参数对象
     * 匹配VSCode插件期望的CoworkflowCodeLens结构
     */
    data class CoworkflowCodeLensParams(
        val documentType: String,
        val actionType: String,
        val range: VSCodeRange,
        val context: Map<String, Any>? = null
    )
    
    /**
     * VSCode兼容的范围参数
     * 匹配VSCode Range对象的结构
     */
    data class VSCodeRange(
        val start: VSCodePosition,
        val end: VSCodePosition
    )
    
    /**
     * VSCode兼容的位置参数
     * 匹配VSCode Position对象的结构
     */
    data class VSCodePosition(
        val line: Int,
        val character: Int
    )
    
    /**
     * 内部使用的范围参数（保持向后兼容）
     */
    data class RangeParams(
        val start: PositionParams,
        val end: PositionParams
    )
    
    /**
     * 内部使用的位置参数（保持向后兼容）
     */
    data class PositionParams(
        val line: Int,
        val character: Int
    )
    
    /**
     * 内部使用的上下文参数（保持向后兼容）
     */
    data class ContextParams(
        val lineNumber: Int? = null,
        val sectionTitle: String? = null,
        val taskId: String? = null
    )
    
    /**
     * 从文件名推断文档类型
     */
    fun inferDocumentType(fileName: String): DocumentType? {
        return when (fileName) {
            "requirements.md" -> DocumentType.REQUIREMENTS
            "design.md" -> DocumentType.DESIGN
            "tasks.md" -> DocumentType.TASKS
            else -> null
        }
    }
    
    /**
     * 获取文件范围
     */
    private fun getFileRange(element: PsiElement): RangeParams {
        val document = FileDocumentManager.getInstance().getDocument(element.containingFile.virtualFile)
        if (document != null) {
            val lineNumber = document.getLineNumber(element.textOffset)
            val lineStartOffset = document.getLineStartOffset(lineNumber)
            val lineEndOffset = document.getLineEndOffset(lineNumber)
            
            return RangeParams(
                start = PositionParams(lineNumber, element.textOffset - lineStartOffset),
                end = PositionParams(lineNumber, lineEndOffset - lineStartOffset)
            )
        }
        
        // 回退到元素范围
        return RangeParams(
            start = PositionParams(0, 0),
            end = PositionParams(0, element.text.length)
        )
    }
    
    /**
     * 为requirements.md的同步操作创建参数
     */
    fun createUpdateSectionParams(e: AnActionEvent): Array<Any>? {
        val project = e.project ?: return null
        val element = e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_ELEMENT) ?: return null
        val virtualFile = element.containingFile.virtualFile
        
        val documentType = inferDocumentType(virtualFile.name)
        if (documentType != DocumentType.REQUIREMENTS && documentType != DocumentType.DESIGN) {
            return null
        }
        
        val internalRange = getFileRange(element)
        val lineNumber = internalRange.start.line
        
        // 创建VSCode兼容的参数结构，使用Map匹配VSCode的CoworkflowCodeLens接口
        val params = mapOf(
            "documentType" to documentType.value,
            "actionType" to ActionType.UPDATE.value,
            "range" to mapOf(
                "start" to mapOf(
                    "line" to internalRange.start.line,
                    "character" to internalRange.start.character
                ),
                "end" to mapOf(
                    "line" to internalRange.end.line,
                    "character" to internalRange.end.character
                )
            ),
            "context" to mapOf(
                "lineNumber" to lineNumber,
                "sectionTitle" to element.text.trim()
            )
        )
        
        return arrayOf(params)
    }
    
    /**
     * 为tasks.md的运行任务操作创建参数
     */
    fun createRunTaskParams(e: AnActionEvent): Array<Any>? {
        val project = e.project ?: return null
        val element = e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_ELEMENT) ?: return null
        val virtualFile = element.containingFile.virtualFile
        
        val documentType = inferDocumentType(virtualFile.name)
        if (documentType != DocumentType.TASKS) {
            return null
        }
        
        val internalRange = getFileRange(element)
        val lineNumber = internalRange.start.line
        
        // 创建VSCode兼容的参数结构，使用Map匹配VSCode的CoworkflowCodeLens接口
        val params = mapOf(
            "documentType" to documentType.value,
            "actionType" to ActionType.RUN.value,
            "range" to mapOf(
                "start" to mapOf(
                    "line" to internalRange.start.line,
                    "character" to internalRange.start.character
                ),
                "end" to mapOf(
                    "line" to internalRange.end.line,
                    "character" to internalRange.end.character
                )
            ),
            "context" to mapOf(
                "lineNumber" to lineNumber
            )
        )
        
        return arrayOf(params)
    }
    
    /**
     * 为运行所有任务操作创建参数
     */
    fun createRunAllTasksParams(e: AnActionEvent): Array<Any>? {
        val project = e.project ?: return null
        val element = e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_ELEMENT) ?: return null
        val virtualFile = element.containingFile.virtualFile
        
        val documentType = inferDocumentType(virtualFile.name)
        if (documentType != DocumentType.TASKS) {
            return null
        }
        
        val internalRange = getFileRange(element)
        val lineNumber = internalRange.start.line
        
        // 创建VSCode兼容的参数结构，使用Map匹配VSCode的CoworkflowCodeLens接口
        val params = mapOf(
            "documentType" to documentType.value,
            "actionType" to ActionType.RUN_ALL.value,
            "range" to mapOf(
                "start" to mapOf(
                    "line" to internalRange.start.line,
                    "character" to internalRange.start.character
                ),
                "end" to mapOf(
                    "line" to internalRange.end.line,
                    "character" to internalRange.end.character
                )
            ),
            "context" to mapOf(
                "lineNumber" to lineNumber
            )
        )
        
        return arrayOf(params)
    }
    
    /**
     * 为重试任务操作创建参数
     */
    fun createRetryTaskParams(e: AnActionEvent): Array<Any>? {
        val project = e.project ?: return null
        val element = e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_ELEMENT) ?: return null
        val virtualFile = element.containingFile.virtualFile
        
        val documentType = inferDocumentType(virtualFile.name)
        if (documentType != DocumentType.TASKS) {
            return null
        }
        
        val internalRange = getFileRange(element)
        val lineNumber = internalRange.start.line
        
        // 添加调试日志
        println("CoworkflowCommandConverter: 创建重试任务参数 - documentType=${documentType.value}, actionType=${ActionType.RETRY.value}")
        println("CoworkflowCommandConverter: 文件名=${virtualFile.name}, 行号=$lineNumber")
        
        // 创建VSCode兼容的参数结构，确保与VSCode的CoworkflowCodeLens接口匹配
        val params = mapOf(
            "documentType" to documentType.value,
            "actionType" to ActionType.RETRY.value,
            "range" to mapOf(
                "start" to mapOf(
                    "line" to internalRange.start.line,
                    "character" to internalRange.start.character
                ),
                "end" to mapOf(
                    "line" to internalRange.end.line,
                    "character" to internalRange.end.character
                )
            ),
            "context" to mapOf(
                "lineNumber" to lineNumber
            )
        )
        
        return arrayOf(params)
    }
    
    /**
     * 为生成测试用例操作创建参数
     */
    fun createRunTestParams(e: AnActionEvent): Array<Any>? {
        val project = e.project ?: return null
        val element = e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_ELEMENT) ?: return null
        val virtualFile = element.containingFile.virtualFile
        
        val documentType = inferDocumentType(virtualFile.name)
        if (documentType != DocumentType.TASKS) {
            return null
        }
        
        val internalRange = getFileRange(element)
        val lineNumber = internalRange.start.line
        
        // 创建VSCode兼容的参数结构，使用Map匹配VSCode的CoworkflowCodeLens接口
        val params = mapOf(
            "documentType" to documentType.value,
            "actionType" to ActionType.RUN_TEST.value,
            "range" to mapOf(
                "start" to mapOf(
                    "line" to internalRange.start.line,
                    "character" to internalRange.start.character
                ),
                "end" to mapOf(
                    "line" to internalRange.end.line,
                    "character" to internalRange.end.character
                )
            ),
            "context" to mapOf(
                "lineNumber" to lineNumber
            )
        )
        
        return arrayOf(params)
    }
    
    /**
     * 执行VSCode命令的通用方法
     */
    fun executeVSCodeCommand(commandId: String, e: AnActionEvent, params: Array<Any>? = null) {
        val project = e.project ?: return
        
        try {
            // 添加调试日志
            println("CoworkflowCommandConverter: 执行命令 - $commandId")
            println("CoworkflowCommandConverter: 参数数量 - ${params?.size ?: 0}")
            if (params != null && params.isNotEmpty()) {
                val firstParam = params[0]
                if (firstParam is CoworkflowCodeLensParams) {
                    println("CoworkflowCommandConverter: documentType=${firstParam.documentType}, actionType=${firstParam.actionType}")
                    println("CoworkflowCommandConverter: range=${firstParam.range}")
                    println("CoworkflowCommandConverter: context=${firstParam.context}")
                }
            }
            executeCommand(commandId, project, *params ?: emptyArray())
        } catch (ex: Exception) {
            println("CoworkflowCommandConverter: 执行命令失败 - $commandId, 错误: ${ex.message}")
            ex.printStackTrace()
        }
    }
    
    /**
     * 从WorkflowMenuAction获取任务文本和状态
     */
    fun extractTaskInfoFromElement(element: PsiElement): Pair<String, String>? {
        val text = element.text ?: return null
        val trimmedText = text.trim()
        
        // 检查是否是任务项
        if (trimmedText.startsWith("- [")) {
            val status = when {
                trimmedText.startsWith("- [ ]") -> "not_started"
                trimmedText.startsWith("- [-]") -> "in_progress"
                trimmedText.startsWith("- [x]") -> "completed"
                else -> null
            }
            
            if (status != null) {
                return Pair(trimmedText, status)
            }
        }
        
        return null
    }
}