package kotlinx.http.server.ktor

import org.jetbrains.ktor.application.*
import org.jetbrains.ktor.content.*
import org.jetbrains.ktor.features.*
import org.jetbrains.ktor.host.*
import org.jetbrains.ktor.routing.*

fun main(args: Array<String>) {
    embeddedServer(CApplicationHost.Factory, port = 9094) {
//        install(CallLogging)
        install(DefaultHeaders)

        install(Routing) {
            val result = ByteArrayContent("Hello, World!\n".toByteArray())

            get("/") {
                call.respond(result)
            }
        }
    }.start(true)
}