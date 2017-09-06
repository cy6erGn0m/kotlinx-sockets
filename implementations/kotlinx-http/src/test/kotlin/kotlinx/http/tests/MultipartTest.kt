package kotlinx.http.tests

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import kotlinx.coroutines.experimental.io.*
import kotlinx.http.*
import org.junit.*
import kotlin.test.*

class MultipartTest {
    @Test
    fun smokeTest() = runBlocking {
        val body = """
            POST /send-message.html HTTP/1.1
            Host: webmail.example.com
            Referer: http://webmail.example.com/send-message.html
            User-Agent: BrowserForDummies/4.67b
            Content-Type: multipart/form-data; boundary=Asrf456BGe4h
            Connection: close
            Keep-Alive: 300

            preamble
            --Asrf456BGe4h
            Content-Disposition: form-data; name="DestAddress"

            recipient@example.com
            --Asrf456BGe4h
            Content-Disposition: form-data; name="MessageTitle"

            Good news
            --Asrf456BGe4h
            Content-Disposition: form-data; name="MessageText"

            See attachments...
            --Asrf456BGe4h
            Content-Disposition: form-data; name="AttachedFile1"; filename="horror-photo-1.jpg"
            Content-Type: image/jpeg

            JFIF first
            --Asrf456BGe4h
            Content-Disposition: form-data; name="AttachedFile2"; filename="horror-photo-2.jpg"
            Content-Type: image/jpeg

            JFIF second
            --Asrf456BGe4h--
            epilogue
            """.trimIndent().lines().joinToString("\r\n")

        val ch = ByteReadChannel(body.toByteArray())
        val request = parseRequest(ch)!!
        val mp = parseMultipart(ch, request.headers)

        val allEvents = ArrayList<MultipartEvent>()
        mp.consumeEach { allEvents.add(it) }

        assertEquals(6, allEvents.size)

        val preamble = allEvents[0] as MultipartEvent.Preamble
        assertEquals("preamble\r\n", preamble.body.inputStream().reader().readText())

        val recipient = allEvents[1] as MultipartEvent.MultipartPart
        assertEquals("recipient@example.com\r\n", recipient.body.readAll().inputStream().reader().readText())

        val title = allEvents[2] as MultipartEvent.MultipartPart
        assertEquals("Good news\r\n", title.body.readAll().inputStream().reader().readText())

        val text = allEvents[3] as MultipartEvent.MultipartPart
        assertEquals("See attachments...\r\n", text.body.readAll().inputStream().reader().readText())

        val jpeg1 = allEvents[4] as MultipartEvent.MultipartPart
        assertEquals("JFIF first\r\n", jpeg1.body.readAll().inputStream().reader().readText())

        val jpeg2 = allEvents[5] as MultipartEvent.MultipartPart
        assertEquals("JFIF second\r\n", jpeg2.body.readAll().inputStream().reader().readText())
    }

    /**
    POST /mp HTTP/1.1
    Host: localhost:9096
    User-Agent: Mozilla/5.0 (X11; Linux x86_64; rv:56.0) Gecko/20100101 Firefox/56.0
    Accept-Language: ru-RU,ru;q=0.5
    Accept-Encoding: gzip, deflate
    Referer: http://localhost:9096/mp
    Content-Type: multipart/form-data; boundary=---------------------------13173666125065307431959751823
    Content-Length: 712
    Cookie: _ga=GA1.1.35265137.1452691312; _mkto_trk=id:426-QVD-114&token:_mch-localhost-1452691311948-23577; Idea-b47799a6=cb4fc716-b57f-46a7-ba57-d86a71c58a40; Idea-a8c10ac5=7516566d-dd79-4c00-82fb-ab1e0c98ead5
    Connection: keep-alive
    Upgrade-Insecure-Requests: 1
    Pragma: no-cache
    Cache-Control: no-cache

    -----------------------------13173666125065307431959751823
    Content-Disposition: form-data; name="title"

    Hello
    -----------------------------13173666125065307431959751823
    Content-Disposition: form-data; name="body"; filename="bug"
    Content-Type: application/octet-stream

    Let's assume we have cwd `/absolute/path/to/dir`, there is node_modules and webpack.config.js on the right place.

    ```
    "resolve": {
        "modules": [
        "node_modules" // this works well
        //"/absolute/path/to/dir/node_modules"   // this doens't
        ]
    }
    ```

    plain webpack works well with both but webpack-dev-server fails with error

    -----------------------------13173666125065307431959751823--

    */
}