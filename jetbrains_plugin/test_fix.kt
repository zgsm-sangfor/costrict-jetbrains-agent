import com.sina.weibo.agent.actions.CoworkflowCommandConverter

fun main() {
    // 测试参数结构是否正确
    val range = CoworkflowCommandConverter.VSCodeRange(
        start = CoworkflowCommandConverter.VSCodePosition(line = 0, character = 0),
        end = CoworkflowCommandConverter.VSCodePosition(line = 10, character = 20)
    )
    
    val params = CoworkflowCommandConverter.CoworkflowCodeLensParams(
        documentType = "requirements",
        actionType = "update",
        range = range,
        context = mapOf(
            "lineNumber" to 5,
            "sectionTitle" to "测试章节"
        )
    )
    
    println("参数结构测试成功:")
    println("documentType: ${params.documentType}")
    println("actionType: ${params.actionType}")
    println("range: ${params.range}")
    println("context: ${params.context}")
    
    // 测试inferDocumentType方法
    val docType1 = CoworkflowCommandConverter.inferDocumentType("requirements.md")
    val docType2 = CoworkflowCommandConverter.inferDocumentType("design.md")
    val docType3 = CoworkflowCommandConverter.inferDocumentType("tasks.md")
    val docType4 = CoworkflowCommandConverter.inferDocumentType("unknown.md")
    
    println("\n文档类型推断测试:")
    println("requirements.md -> $docType1")
    println("design.md -> $docType2")
    println("tasks.md -> $docType3")
    println("unknown.md -> $docType4")
}