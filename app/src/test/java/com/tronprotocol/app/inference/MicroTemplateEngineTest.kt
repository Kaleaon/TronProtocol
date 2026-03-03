package com.tronprotocol.app.inference

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MicroTemplateEngineTest {

    private lateinit var engine: MicroTemplateEngine

    @Before
    fun setUp() {
        engine = MicroTemplateEngine()
    }

    @Test
    fun `plain text renders unchanged`() {
        assertEquals("Hello World", engine.render("Hello World", emptyMap()))
    }

    @Test
    fun `variable substitution`() {
        val result = engine.render("Hello {{name}}!", mapOf("name" to "Tron"))
        assertEquals("Hello Tron!", result)
    }

    @Test
    fun `missing variable renders empty`() {
        val result = engine.render("Hello {{name}}!", emptyMap())
        assertEquals("Hello !", result)
    }

    @Test
    fun `multiple variables`() {
        val result = engine.render(
            "{{greeting}} {{name}}, age {{age}}",
            mapOf("greeting" to "Hi", "name" to "Tron", "age" to 5)
        )
        assertEquals("Hi Tron, age 5", result)
    }

    @Test
    fun `escaped HTML in variable output`() {
        val result = engine.render("Value: {{html}}", mapOf("html" to "<b>bold</b>"))
        assertEquals("Value: &lt;b&gt;bold&lt;/b&gt;", result)
    }

    @Test
    fun `raw variable output with triple braces`() {
        val result = engine.render("Value: {{{html}}}", mapOf("html" to "<b>bold</b>"))
        assertEquals("Value: <b>bold</b>", result)
    }

    @Test
    fun `if block with truthy condition`() {
        val result = engine.render(
            "Start {{#if show}}visible{{/if}} End",
            mapOf("show" to true)
        )
        assertEquals("Start visible End", result)
    }

    @Test
    fun `if block with falsy condition`() {
        val result = engine.render(
            "Start {{#if show}}visible{{/if}} End",
            mapOf("show" to false)
        )
        assertEquals("Start  End", result)
    }

    @Test
    fun `if-else block`() {
        val trueResult = engine.render(
            "{{#if active}}ON{{else}}OFF{{/if}}",
            mapOf("active" to true)
        )
        assertEquals("ON", trueResult)

        val falseResult = engine.render(
            "{{#if active}}ON{{else}}OFF{{/if}}",
            mapOf("active" to false)
        )
        assertEquals("OFF", falseResult)
    }

    @Test
    fun `each loop`() {
        val result = engine.render(
            "Items: {{#each items}}[{{.}}]{{/each}}",
            mapOf("items" to listOf("a", "b", "c"))
        )
        assertEquals("Items: [a][b][c]", result)
    }

    @Test
    fun `each loop with index`() {
        val result = engine.render(
            "{{#each items}}{{@index}}:{{.}} {{/each}}",
            mapOf("items" to listOf("x", "y"))
        )
        assertEquals("0:x 1:y ", result)
    }

    @Test
    fun `each loop with empty list`() {
        val result = engine.render(
            "Items: {{#each items}}[{{.}}]{{/each}}",
            mapOf("items" to emptyList<String>())
        )
        assertEquals("Items: ", result)
    }

    @Test
    fun `partial inclusion`() {
        val partials = mapOf("header" to "=== {{title}} ===")
        val result = engine.render(
            "{{> header}}\nBody here",
            mapOf("title" to "Test"),
            partials
        )
        assertEquals("=== Test ===\nBody here", result)
    }

    @Test
    fun `nested if blocks`() {
        val result = engine.render(
            "{{#if a}}A{{#if b}}B{{/if}}{{/if}}",
            mapOf("a" to true, "b" to true)
        )
        assertEquals("AB", result)
    }

    @Test
    fun `if truthy with non-empty string`() {
        val result = engine.render(
            "{{#if name}}Hello {{name}}{{/if}}",
            mapOf("name" to "Tron")
        )
        assertEquals("Hello Tron", result)
    }

    @Test
    fun `if falsy with empty string`() {
        val result = engine.render(
            "{{#if name}}Hello{{else}}No name{{/if}}",
            mapOf("name" to "")
        )
        assertEquals("No name", result)
    }

    @Test
    fun `if falsy with zero number`() {
        val result = engine.render(
            "{{#if count}}Has items{{else}}Empty{{/if}}",
            mapOf("count" to 0)
        )
        assertEquals("Empty", result)
    }

    @Test
    fun `isTruthy checks`() {
        assertTrue(MicroTemplateEngine.isTruthy(true))
        assertFalse(MicroTemplateEngine.isTruthy(false))
        assertTrue(MicroTemplateEngine.isTruthy("hello"))
        assertFalse(MicroTemplateEngine.isTruthy(""))
        assertTrue(MicroTemplateEngine.isTruthy(1))
        assertFalse(MicroTemplateEngine.isTruthy(0))
        assertTrue(MicroTemplateEngine.isTruthy(listOf("a")))
        assertFalse(MicroTemplateEngine.isTruthy(emptyList<String>()))
        assertFalse(MicroTemplateEngine.isTruthy(null))
    }

    @Test
    fun `resolveValue handles types`() {
        assertEquals("hello", MicroTemplateEngine.resolveValue("x", mapOf("x" to "hello")))
        assertEquals("42", MicroTemplateEngine.resolveValue("x", mapOf("x" to 42)))
        assertEquals("true", MicroTemplateEngine.resolveValue("x", mapOf("x" to true)))
        assertEquals("a, b", MicroTemplateEngine.resolveValue("x", mapOf("x" to listOf("a", "b"))))
        assertEquals("", MicroTemplateEngine.resolveValue("missing", emptyMap()))
    }

    @Test
    fun `complex template with multiple features`() {
        val template = """{{> header}}
{{#if hasItems}}Items:
{{#each items}}  - {{.}}
{{/each}}{{else}}No items found.{{/if}}
Total: {{total}}"""

        val result = engine.render(
            template,
            mapOf(
                "title" to "Report",
                "hasItems" to true,
                "items" to listOf("Alpha", "Beta"),
                "total" to 2
            ),
            mapOf("header" to "# {{title}}")
        )

        assertTrue(result.contains("# Report"))
        assertTrue(result.contains("- Alpha"))
        assertTrue(result.contains("- Beta"))
        assertTrue(result.contains("Total: 2"))
    }
}
