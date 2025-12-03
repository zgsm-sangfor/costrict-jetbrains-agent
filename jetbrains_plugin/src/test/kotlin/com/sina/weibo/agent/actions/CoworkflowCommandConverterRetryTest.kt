// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.sina.weibo.agent.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test
import org.mockito.Mockito.*

/**
 * 测试重试任务参数转换的修复
 * 验证参数结构与VSCode插件的CoworkflowCodeLens接口匹配
 */
class CoworkflowCommandConverterRetryTest : BasePlatformTestCase() {

    @Test
    fun testCreateRetryTaskParamsWithMapStructure() {
        // 创建模拟的PSI元素和文件
        val mockPsiElement = mock(PsiElement::class.java)
        val mockPsiFile = mock(PsiFile::class.java)
        val mockVirtualFile = com.intellij.openapi.vfs.VirtualFile("tasks.md")

        // 设置模拟行为
        `when`(mockPsiElement.containingFile).thenReturn(mockPsiFile)
        `when`(mockPsiFile.virtualFile).thenReturn(mockVirtualFile)

        // 创建模拟的AnActionEvent
        val mockEvent = mock(AnActionEvent::class.java)
        `when`(mockEvent.project).thenReturn(project)
        `when`(mockEvent.getData(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_ELEMENT))
            .thenReturn(mockPsiElement)

        // 调用createRetryTaskParams
        val params = CoworkflowCommandConverter.createRetryTaskParams(mockEvent)

        // 验证参数不为空
        assertNotNull("重试任务参数不应为空", params)
        assertTrue("参数数组应包含一个元素", params!!.isNotEmpty())

        // 验证参数是Map结构（修复后的结构）
        val param = params[0]
        assertTrue("参数应该是Map类型", param is Map<*, *>)

        @Suppress("UNCHECKED_CAST")
        val paramMap = param as Map<String, Any>

        // 验证必需字段
        assertTrue("参数应包含documentType", paramMap.containsKey("documentType"))
        assertTrue("参数应包含actionType", paramMap.containsKey("actionType"))
        assertTrue("参数应包含range", paramMap.containsKey("range"))
        assertTrue("参数应包含context", paramMap.containsKey("context"))

        // 验证字段值
        assertEquals("documentType应为tasks", "tasks", paramMap["documentType"])
        assertEquals("actionType应为retry", "retry", paramMap["actionType"])

        // 验证range结构
        val rangeMap = paramMap["range"] as Map<String, Any>
        assertTrue("range应包含start", rangeMap.containsKey("start"))
        assertTrue("range应包含end", rangeMap.containsKey("end"))

        val startMap = rangeMap["start"] as Map<String, Any>
        val endMap = rangeMap["end"] as Map<String, Any>

        assertTrue("start应包含line", startMap.containsKey("line"))
        assertTrue("start应包含character", startMap.containsKey("character"))
        assertTrue("end应包含line", endMap.containsKey("line"))
        assertTrue("end应包含character", endMap.containsKey("character"))

        // 验证context结构
        val contextMap = paramMap["context"] as Map<String, Any>
        assertTrue("context应包含lineNumber", contextMap.containsKey("lineNumber"))
    }

    @Test
    fun testDocumentTypeInferenceForTasksFile() {
        val tasksDocType = CoworkflowCommandConverter.inferDocumentType("tasks.md")
        assertEquals("tasks.md应被识别为TASKS类型", 
            CoworkflowCommandConverter.DocumentType.TASKS, tasksDocType)
        assertEquals("TASKS类型的值应为tasks", "tasks", tasksDocType?.value)
    }

    @Test
    fun testAllCommandParamsUseMapStructure() {
        // 验证所有命令参数创建函数都使用Map结构
        val mockPsiElement = mock(PsiElement::class.java)
        val mockPsiFile = mock(PsiFile::class.java)
        val mockVirtualFile = com.intellij.openapi.vfs.VirtualFile("tasks.md")

        `when`(mockPsiElement.containingFile).thenReturn(mockPsiFile)
        `when`(mockPsiFile.virtualFile).thenReturn(mockVirtualFile)

        val mockEvent = mock(AnActionEvent::class.java)
        `when`(mockEvent.project).thenReturn(project)
        `when`(mockEvent.getData(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_ELEMENT))
            .thenReturn(mockPsiElement)

        // 测试createRunTaskParams
        val runParams = CoworkflowCommandConverter.createRunTaskParams(mockEvent)
        assertNotNull("运行任务参数不应为空", runParams)
        if (runParams != null) {
            assertTrue("运行任务参数应为Map类型", runParams[0] is Map<*, *>)
        }

        // 测试createRunAllTasksParams
        val runAllParams = CoworkflowCommandConverter.createRunAllTasksParams(mockEvent)
        assertNotNull("运行所有任务参数不应为空", runAllParams)
        if (runAllParams != null) {
            assertTrue("运行所有任务参数应为Map类型", runAllParams[0] is Map<*, *>)
        }

        // 测试createRunTestParams
        val testParams = CoworkflowCommandConverter.createRunTestParams(mockEvent)
        assertNotNull("测试参数不应为空", testParams)
        if (testParams != null) {
            assertTrue("测试参数应为Map类型", testParams[0] is Map<*, *>)
        }
    }

    @Test
    fun testDocumentTypeCompatibilityWithVSCode() {
        // 验证IntelliJ的DocumentType枚举与VSCode的CoworkflowDocumentType匹配
        assertEquals("REQUIREMENTS应匹配VSCode的requirements", 
            "requirements", CoworkflowCommandConverter.DocumentType.REQUIREMENTS.value)
        assertEquals("DESIGN应匹配VSCode的design", 
            "design", CoworkflowCommandConverter.DocumentType.DESIGN.value)
        assertEquals("TASKS应匹配VSCode的tasks", 
            "tasks", CoworkflowCommandConverter.DocumentType.TASKS.value)
    }

    @Test
    fun testActionTypeCompatibilityWithVSCode() {
        // 验证IntelliJ的ActionType枚举与VSCode的CoworkflowActionType匹配
        assertEquals("UPDATE应匹配VSCode的update", 
            "update", CoworkflowCommandConverter.ActionType.UPDATE.value)
        assertEquals("RUN应匹配VSCode的run", 
            "run", CoworkflowCommandConverter.ActionType.RUN.value)
        assertEquals("RETRY应匹配VSCode的retry", 
            "retry", CoworkflowCommandConverter.ActionType.RETRY.value)
        assertEquals("RUN_ALL应匹配VSCode的run_all", 
            "run_all", CoworkflowCommandConverter.ActionType.RUN_ALL.value)
        assertEquals("RUN_TEST应匹配VSCode的run_test", 
            "run_test", CoworkflowCommandConverter.ActionType.RUN_TEST.value)
    }
}