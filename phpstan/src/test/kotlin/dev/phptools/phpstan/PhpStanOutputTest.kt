package dev.phptools.phpstan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Фикстуры сняты с PHPStan 2.2.16 + Larastan 3.12 (playground/laravel). */
class PhpStanOutputTest {

    private val withErrors = """
        {"totals":{"errors":0,"file_errors":2},"files":{"/home/u/app/app/Broken.php":{"errors":2,"messages":[
          {"message":"Call to an undefined method App\\Broken::nope().","line":3,"ignorable":true,"identifier":"method.notFound","tip":"Learn more"},
          {"message":"Method App\\Broken::x() should return int but returns string.","line":3,"ignorable":true,"identifier":"return.type"}
        ]}},"errors":[]}
    """.trimIndent()

    private fun ok(result: PhpStanResult) = assertInstanceOf(PhpStanResult.Ok::class.java, result)

    @Test
    fun `errors are parsed`() {
        val result = ok(PhpStanOutput.parse(1, withErrors, ""))
        val messages = result.files.getValue("/home/u/app/app/Broken.php")

        assertEquals(2, messages.size)
        assertEquals(PhpStanMessage("Call to an undefined method App\\Broken::nope().", 3, true, "method.notFound", "Learn more"), messages[0])
        assertEquals(null, messages[1].tip)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `no errors`() {
        val result = ok(PhpStanOutput.parse(0, """{"totals":{"errors":0,"file_errors":0},"files":[],"errors":[]}""", ""))

        assertTrue(result.files.isEmpty())
    }

    @Test
    fun `line null is a file level error`() {
        val json = """{"files":{"/a.php":{"messages":[{"message":"Whole file","line":null,"ignorable":false}]}},"errors":[]}"""

        val message = ok(PhpStanOutput.parse(1, json, "")).files.getValue("/a.php").single()

        assertEquals(null, message.line)
        assertEquals(null, message.identifier)
    }

    @Test
    fun `top level errors are returned`() {
        val json = """{"totals":{"errors":1,"file_errors":0},"files":[],"errors":["Autoloader failed"]}"""

        assertEquals(listOf("Autoloader failed"), ok(PhpStanOutput.parse(1, json, "")).errors)
    }

    @Test
    fun `garbage before json is skipped`() {
        val stdout = "PHP Warning: something in foo.php\nNote: Using configuration file x.\n$withErrors"

        assertEquals(1, ok(PhpStanOutput.parse(1, stdout, "")).files.size)
    }

    @Test
    fun `empty stdout is a failure`() {
        val result = PhpStanOutput.parse(1, "", "Invalid configuration:\nUnexpected item 'parameters › nonexistentParam'.")

        assertEquals(PhpStanResult.Failed("Invalid configuration:\nUnexpected item 'parameters › nonexistentParam'."), result)
    }

    @Test
    fun `unexpected exit code is a failure`() {
        assertInstanceOf(PhpStanResult.Failed::class.java, PhpStanOutput.parse(255, withErrors, "PHP Fatal error"))
    }

    @Test
    fun `excluded file gives no files`() {
        val stderr = "Note: Using configuration file /x/phpstan.neon.\n\n [ERROR] No files found to analyse.\n"

        assertEquals(PhpStanResult.NoFiles, PhpStanOutput.parse(1, "", stderr))
    }

    @Test
    fun `old phpstan without tmp-file`() {
        val stderr = "\n  The \"--tmp-file\" option does not exist.  \n\nanalyse [-c|--configuration CONFIGURATION] ..."

        assertEquals(PhpStanResult.TmpFileUnsupported, PhpStanOutput.parse(1, "", stderr))
    }

    @Test
    fun `agent format is understood`() {
        val json = """{"tool":"phpstan","result":"failed","errors":2,"error_details":{"/a/Broken.php":[
            {"line":3,"message":"Call to an undefined method.","identifier":"method.notFound"}]},"instructions":"..."}"""

        val message = ok(PhpStanOutput.parse(1, json, "")).files.getValue("/a/Broken.php").single()

        assertEquals(PhpStanMessage("Call to an undefined method.", 3, false, "method.notFound", null), message)
    }

    @Test
    fun `messages are matched by relative path suffix`() {
        val files = mapOf(
            "/var/www/html/app/Models/User.php" to listOf(PhpStanMessage("a", 1, true, null, null)),
            "/var/www/html/app/User.php" to listOf(PhpStanMessage("b", 1, true, null, null)),
        )

        assertEquals("a", PhpStanOutput.messagesFor(files, "app/Models/User.php").single().message)
        assertEquals("b", PhpStanOutput.messagesFor(files, "app/User.php").single().message)
    }

    @Test
    fun `single entry is used when path does not match`() {
        val files = mapOf("/tmp/other.php" to listOf(PhpStanMessage("a", 1, true, null, null)))

        assertEquals("a", PhpStanOutput.messagesFor(files, "app/User.php").single().message)
        assertTrue(PhpStanOutput.messagesFor(files + ("/x.php" to emptyList()), "app/User.php").isEmpty())
    }
}
