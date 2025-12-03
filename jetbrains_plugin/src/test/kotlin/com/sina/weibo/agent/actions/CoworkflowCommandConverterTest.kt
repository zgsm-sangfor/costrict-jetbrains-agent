// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package com.sina.weibo.agent.actions

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.TempDirTestFixture
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.junit.After

/**
 * CoworkflowCommandConverter 测试类
 * 测试参数转换和命令调用的正确性
 */
class CoworkflowCommandConverterTest : BasePlatformTestCase() {
    
    private lateinit var tempDirTestFixture: TempDirTestFixture
    
    @Before
    override fun setUp() {
        super.setUp()
        tempDirTestFixture = createTempDirTestFixture()
        tempDirTestFixture.setUp()
    }
    
    @After
    override fun tearDown() {
        tempDirTestFixture.tearDown()
        super.tearDown()
    }
    
    @Test
    fun testCreateUpdateSectionParamsForRequirements() {
        // 创建requirements.md文件
        val requirementsFile = tempDirTestFixture.createFile("requirements.md", "# 需求标题\n\n需求描述内容")
        val psiFile = PsiManager.getInstance(project).findFile(requirementsFile)
        assertNotNull(psiFile)
        
        // 创建AnActionEvent模拟
        val presentation = Presentation()
        val dataContext = object : com.intellij.openapi.actionSystem.DataContext {
            override fun getData(dataId: String): Any? {
                when (dataId) {
                    com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.name -> return project
                    com.intellij.openapi.actionSystem.CommonDataKeys.PSI_ELEMENT.name -> return psiFile?.firstChild
                    else -> return null
                }
            }
        }
        
        val actionEvent = AnActionEvent(null, dataContext, "test", presentation, com.intellij.openapi.actionSystem.ActionPlaces.UNKNOWN, 1)
        
        // 测试参数转换
        val params = CoworkflowCommandConverter.createUpdateSectionParams(actionEvent)
        assertNotNull(params)
        assertEquals(1, params?.size)
        
        val param = params?.get(0) as CoworkflowCommandConverter.CoworkflowCodeLensParams
        assertEquals("requirements", param.documentType)
        assertEquals("update", param.actionType)
        assertNotNull(param.range)
        assertNotNull(param.context)
        assertTrue(param.context?.containsKey("lineNumber") == true)
        assertTrue(param.context?.containsKey("sectionTitle") == true)
    }
    
    @Test
    fun testCreateUpdateSectionParamsForDesign() {
        // 创建design.md文件
        val designFile = tempDirTestFixture.createFile("design.md", "# 设计标题\n\n设计描述内容")
        val psiFile = PsiManager.getInstance(project).findFile(designFile)
        assertNotNull(psiFile)
        
        // 创建AnActionEvent模拟
        val presentation = Presentation()
        val dataContext = object : com.intellij.openapi.actionSystem.DataContext {
            override fun getData(dataId: String): Any? {
                when (dataId) {
                    com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.name -> return project
                    com.intellij.openapi.actionSystem.CommonDataKeys.PSI_ELEMENT.name -> return psiFile?.firstChild
                    else -> return null
                }
            }
        }
        
        val actionEvent = AnActionEvent(null, dataContext, "test", presentation, com.intellij.openapi.actionSystem.ActionPlaces.UNKNOWN, 1)
        
        // 测试参数转换
        val params = CoworkflowCommandConverter.createUpdateSectionParams(actionEvent)
        assertNotNull(params)
        assertEquals(1, params?.size)
        
        val param = params?.get(0) as CoworkflowCommandConverter.CoworkflowCodeLensParams
        assertEquals("design", param.documentType)
        assertEquals("update", param.actionType)
        assertNotNull(param.range)
        assertNotNull(param.context)
        assertTrue(param.context?.containsKey("lineNumber") == true)
        assertTrue(param.context?.containsKey("sectionTitle") == true)
    }
    
    @Test
    fun testCreateRunTaskParams() {
        // 创建tasks.md文件
        val tasksFile = tempDirTestFixture.createFile("tasks.md", "- [ ] 任务1\n- [x] 任务2")
        val psiFile = PsiManager.getInstance(project).findFile(tasksFile)
        assertNotNull(psiFile)
        
        // 创建AnActionEvent模拟
        val presentation = Presentation()
        val dataContext = object : com.intellij.openapi.actionSystem.DataContext {
            override fun getData(dataId: String): Any? {
                when (dataId) {
                    com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.name -> return project
                    com.intellij.openapi.actionSystem.CommonDataKeys.PSI_ELEMENT.name -> return psiFile?.firstChild
                    else -> return null
                }
            }
        }
        
        val actionEvent = AnActionEvent(null, dataContext, "test", presentation, com.intellij.openapi.actionSystem.ActionPlaces.UNKNOWN, 1)
        
        // 测试参数转换
        val params = CoworkflowCommandConverter.createRunTaskParams(actionEvent)
        assertNotNull(params)
        assertEquals(1, params?.size)
        
        val param = params?.get(0) as CoworkflowCommandConverter.CoworkflowCodeLensParams
        assertEquals("tasks", param.documentType)
        assertEquals("run", param.actionType)
        assertNotNull(param.range)
        assertNotNull(param.context)
        assertTrue(param.context?.containsKey("lineNumber") == true)
    }
    
    @Test
    fun testCreateRunAllTasksParams() {
        // 创建tasks.md文件
        val tasksFile = tempDirTestFixture.createFile("tasks.md", "- [ ] 任务1\n- [x] 任务2")
        val psiFile = PsiManager.getInstance(project).findFile(tasksFile)
        assertNotNull(psiFile)
        
        // 创建AnActionEvent模拟
        val presentation = Presentation()
        val dataContext = object : com.intellij.openapi.actionSystem.DataContext {
            override fun getData(dataId: String): Any? {
                when (dataId) {
                    com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.name -> return project
                    com.intellij.openapi.actionSystem.CommonDataKeys.PSI_ELEMENT.name -> return psiFile?.firstChild
                    else -> return null
                }
            }
        }
        
        val actionEvent = AnActionEvent(null, dataContext, "test", presentation, com.intellij.openapi.actionSystem.ActionPlaces.UNKNOWN, 1)
        
        // 测试参数转换
        val params = CoworkflowCommandConverter.createRunAllTasksParams(actionEvent)
        assertNotNull(params)
        assertEquals(1, params?.size)
        
        val param = params?.get(0) as CoworkflowCommandConverter.CoworkflowCodeLensParams
        assertEquals("tasks", param.documentType)
        assertEquals("run_all", param.actionType)
        assertNotNull(param.range)
        assertNotNull(param.context)
        assertTrue(param.context?.isEmpty() == true)
    }
    
    @Test
    fun testCreateRetryTaskParams() {
        // 创建tasks.md文件
        val tasksFile = tempDirTestFixture.createFile("tasks.md", "- [- ] 进行中的任务\n- [x] 已完成的任务")
        val psiFile = PsiManager.getInstance(project).findFile(tasksFile)
        assertNotNull(psiFile)
        
        // 创建AnActionEvent模拟
        val presentation = Presentation()
        val dataContext = object : com.intellij.openapi.actionSystem.DataContext {
            override fun getData(dataId: String): Any? {
                when (dataId) {
                    com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.name -> return project
                    com.intellij.openapi.actionSystem.CommonDataKeys.PSI_ELEMENT.name -> return psiFile?.firstChild
                    else -> return null
                }
            }
        }
        
        val actionEvent = AnActionEvent(null, dataContext, "test", presentation, com.intellij.openapi.actionSystem.ActionPlaces.UNKNOWN, 1)
        
        // 测试参数转换
        val params = CoworkflowCommandConverter.createRetryTaskParams(actionEvent)
        assertNotNull(params)
        assertEquals(1, params?.size)
        
        val param = params?.get(0) as CoworkflowCommandConverter.CoworkflowCodeLensParams
        assertEquals("tasks", param.documentType)
        assertEquals("retry", param.actionType)
        assertNotNull(param.range)
        assertNotNull(param.context)
        assertTrue(param.context?.containsKey("lineNumber") == true)
    }
    
    @Test
    fun testCreateRunTestParams() {
        // 创建tasks.md文件
        val tasksFile = tempDirTestFixture.createFile("tasks.md", "- [ ] 待测试的任务")
        val psiFile = PsiManager.getInstance(project).findFile(tasksFile)
        assertNotNull(psiFile)
        
        // 创建AnActionEvent模拟
        val presentation = Presentation()
        val dataContext = object : com.intellij.openapi.actionSystem.DataContext {
            override fun getData(dataId: String): Any? {
                when (dataId) {
                    com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.name -> return project
                    com.intellij.openapi.actionSystem.CommonDataKeys.PSI_ELEMENT.name -> return psiFile?.firstChild
                    else -> return null
                }
            }
        }
        
        val actionEvent = AnActionEvent(null, dataContext, "test", presentation, com.intellij.openapi.actionSystem.ActionPlaces.UNKNOWN, 1)
        
        // 测试参数转换
        val params = CoworkflowCommandConverter.createRunTestParams(actionEvent)
        assertNotNull(params)
        assertEquals(1, params?.size)
        
        val param = params?.get(0) as CoworkflowCommandConverter.CoworkflowCodeLensParams
        assertEquals("tasks", param.documentType)
        assertEquals("run_test", param.actionType)
        assertNotNull(param.range)
        assertNotNull(param.context)
        assertTrue(param.context?.containsKey("lineNumber") == true)
    }
    
    @Test
    fun testExtractTaskInfoFromElement() {
        // 创建tasks.md文件
        val tasksFile = tempDirTestFixture.createFile("tasks.md", "- [ ] 待执行任务\n- [- ] 进行中任务\n- [x] 已完成任务")
        val psiFile = PsiManager.getInstance(project).findFile(tasksFile)
        assertNotNull(psiFile)
        
        // 测试待执行任务
        val pendingTask = psiFile?.findElementAt(0)
        val pendingInfo = CoworkflowCommandConverter.extractTaskInfoFromElement(pendingTask!!)
        assertNotNull(pendingInfo)
        assertEquals("- [ ] 待执行任务", pendingInfo?.first)
        assertEquals("not_started", pendingInfo?.second)
        
        // 测试进行中任务
        val inProgressTask = psiFile?.findElementAt(psiFile.text.indexOf("- [- ]"))
        val inProgressInfo = CoworkflowCommandConverter.extractTaskInfoFromElement(inProgressTask!!)
        assertNotNull(inProgressInfo)
        assertEquals("- [- ] 进行中任务", inProgressInfo?.first)
        assertEquals("in_progress", inProgressInfo?.second)
        
        // 测试已完成任务
        val completedTask = psiFile?.findElementAt(psiFile.text.indexOf("- [x]"))
        val completedInfo = CoworkflowCommandConverter.extractTaskInfoFromElement(completedTask!!)
        assertNotNull(completedInfo)
        assertEquals("- [x] 已完成任务", completedInfo?.first)
        assertEquals("completed", completedInfo?.second)
    }
    
    @Test
    fun testInferDocumentType() {
        assertEquals(CoworkflowCommandConverter.DocumentType.REQUIREMENTS, 
                    CoworkflowCommandConverter.inferDocumentType("requirements.md"))
        assertEquals(CoworkflowCommandConverter.DocumentType.DESIGN, 
                    CoworkflowCommandConverter.inferDocumentType("design.md"))
        assertEquals(CoworkflowCommandConverter.DocumentType.TASKS, 
                    CoworkflowCommandConverter.inferDocumentType("tasks.md"))
        assertNull(CoworkflowCommandConverter.inferDocumentType("other.md"))
    }
}