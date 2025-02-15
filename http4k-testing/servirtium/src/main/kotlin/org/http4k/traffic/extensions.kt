package org.http4k.traffic

import org.http4k.core.Body
import org.http4k.core.HttpHandler
import org.http4k.core.HttpMessage
import org.http4k.core.HttpMessage.Companion.HTTP_1_1
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.NOT_IMPLEMENTED
import org.http4k.core.parse
import org.http4k.lens.Header.CONTENT_TYPE
import org.http4k.servirtium.InteractionOptions
import org.http4k.servirtium.InteractionOptions.Companion.Defaults
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import java.util.function.Supplier

fun Replay.replayingMatchingContent(manipulations: (Request) -> Request = { it }): HttpHandler {
    // TODO: avoid double execution of `requests()`
    val interactionCount = requests().zip(responses()).count()
    val interactions = requests().zip(responses()).iterator()
    val count = AtomicInteger()

    return { received: Request ->
        val index = count.getAndIncrement()

        val actual = manipulations(received).toString()

        when {
            interactions.hasNext() -> {
                val (expectedReq, response) = interactions.next()

                if (expectedReq.toString() == actual) response
                else renderMismatch(index, expectedReq.toString(), actual)
            }
            else -> renderUnexpectedInteraction(interactionCount, index + 1, actual)
        }
    }
}

private fun renderMismatch(index: Int, expectedReq: String, actual: String) = Response(NOT_IMPLEMENTED).body(
    "Unexpected request received for Interaction $index ==> " +
        "expected: <$expectedReq> but was: <$actual>")

/**
 * Interaction was called more times than there are interactions
 */
private fun renderUnexpectedInteraction(interactions: Int, count: Int, actual: String) = Response(NOT_IMPLEMENTED).body(
    "Have $interactions interaction(s) in the script but called $count times. Unexpected interaction: <$actual>"
)

/**
 * Write HTTP traffic to disk in Servirtium markdown format.
 */
fun Sink.Companion.Servirtium(target: Consumer<ByteArray>,
                              options: InteractionOptions) = object : Sink {
    private val count = AtomicInteger()
    override fun set(request: Request, response: Response) {
        val manipulatedRequest = options.modify(request)
        val manipulatedResponse = options.modify(response)
        target.accept(
            manipulatedRequest.header() +
                manipulatedRequest.encodedBody() +
                manipulatedResponse.middle() +
                manipulatedResponse.encodedBody() +
                footer()
        )
    }

    private fun Response.middle() = ("\n```\n\n" +
        headerLine<Response>() + ":\n" +
        headerBlock() + "\n" +
        bodyLine<Response>() + " (${status.code}: ${(CONTENT_TYPE(this)?.toHeaderValue()
        .orEmpty())}):\n\n```\n"
        ).toByteArray()

    private fun Request.header() = ("## Interaction ${count.getAndIncrement()}: ${method.name} $uri\n\n" +
        headerLine<Request>() + ":\n" +
        headerBlock() + "\n" +
        bodyLine<Request>() + " (${CONTENT_TYPE(this)?.toHeaderValue() ?: ""}):\n" +
        "\n```\n").toByteArray()

    private fun footer() = "\n```\n\n".toByteArray()

    private fun HttpMessage.headerBlock() = "\n```\n${headers.joinToString("\n") {
        it.first + ": " + (it.second ?: "")
    }}\n```\n"

    private fun HttpMessage.encodedBody() =
        CONTENT_TYPE(this)
            ?.takeIf { options.isBinary(it) }
            ?.let { Base64.getEncoder().encode(body.payload.array()) }
            ?: bodyString().toByteArray()
}

/**
 * Read HTTP traffic from disk in Servirtium markdown format.
 */
fun Replay.Companion.Servirtium(output: Supplier<ByteArray>, options: InteractionOptions = Defaults) = object : Replay {

    override fun requests() = output.parseInteractions { it.first }
        .map { req ->
            CONTENT_TYPE(req)
                ?.takeIf { options.isBinary(it) }
                ?.let { req.body(Body(ByteBuffer.wrap(Base64.getDecoder().decode(req.bodyString())))) }
                ?: req
        }

    override fun responses() = output.parseInteractions { it.second }
        .map { req ->
            CONTENT_TYPE(req)
                .takeIf { options.isBinary(it) }
                ?.let { req.body(Body(ByteBuffer.wrap(Base64.getDecoder().decode(req.bodyString())))) }
                ?: req
        }

    private fun <T : HttpMessage> Supplier<ByteArray>.parseInteractions(fn: (Pair<Request, Response>) -> T) =
        String(get())
            .split(Regex("## Interaction \\d+: "))
            .filter { it.contains("```") }
            .map {
                val sections = it.split("```").map { it.byteInputStream().reader().readLines() }

                val requestString = listOf(
                    listOf(sections[0][0] + " " + HTTP_1_1),
                    sections[1].dropWhile(String::isBlank) + "\r\n",
                    listOf(sections[3].dropWhile(String::isBlank).joinToString("\n"))
                ).flatten().joinToString("\r\n")

                val req = Request.parse(requestString)

                val resp = Response.parse(
                    listOf(
                        listOf(HTTP_1_1 +
                            " " +
                            sections[6].first { it.startsWith(bodyLine<Response>()) }.split('(', ':')[1] +
                            " "
                        ),
                        sections[5].dropWhile(String::isBlank) + "\r\n",
                        listOf(sections[7].dropWhile(String::isBlank).joinToString("\n"))
                    ).flatten().joinToString("\r\n")
                )
                req to resp
            }
            .map(fn)
            .asSequence()
}

private inline fun <reified T : HttpMessage> headerLine() = """### ${T::class.java.simpleName} headers recorded for playback"""
private inline fun <reified T : HttpMessage> bodyLine() = """### ${T::class.java.simpleName} body recorded for playback"""
