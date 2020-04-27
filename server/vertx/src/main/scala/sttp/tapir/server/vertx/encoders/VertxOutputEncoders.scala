package sttp.tapir.server.vertx.encoders

import java.io.{File, InputStream}
import java.nio.ByteBuffer
import java.nio.charset.Charset

import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpHeaders
import io.vertx.scala.core.http.HttpServerResponse
import io.vertx.scala.core.streams.ReadStream
import io.vertx.scala.ext.web.RoutingContext
import sttp.model.{Header, Part}
import sttp.tapir.internal.ParamsAsAny
import sttp.tapir.server.ServerDefaults
import sttp.tapir.server.internal.{EncodeOutputBody, EncodeOutputs, OutputValues}
import sttp.tapir.server.vertx.VertxEndpointOptions
import sttp.tapir.{CodecFormat, EndpointOutput, RawBodyType}

/**
 * All the necessary methods to write Endpoint.outputs to Vert.x HttpServerResponse
 * Contains:
 *   - Headers handling
 *   - Body handling (string, binaries, files, multipart, ...)
 *   - Stream handling
 */
object VertxOutputEncoders {

  type RoutingContextHandler = RoutingContext => Unit
  type RoutingContextHandlerWithLength = Option[Long] => RoutingContext => Unit

  /**
   * Creates a function, that given a RoutingContext will write the result to its response, according to the endpoint definition
   * @param output the endpoint definition
   * @param result the result to encode
   * @param isError boolean indicating if the result is an error or not
   * @param logWhenHandled an optional function that will be invoked once the response has been successfully written, with the status code returned
   * @tparam O type of the result to encode
   * @return a function, that given a RoutingContext will write the output to its HTTP response
   */
  private [vertx] def apply[O](output: EndpointOutput[O], result: O, isError: Boolean = false, logWhenHandled: Int => Unit = { _ => })
                              (implicit endpointOptions: VertxEndpointOptions): RoutingContextHandler = { rc =>
    val resp = rc.response
    val options: OutputValues[RoutingContextHandlerWithLength] = OutputValues.empty
    try {
      var outputValues = encodeOutputs(output, ParamsAsAny(result), options)
      if (isError && outputValues.statusCode.isEmpty) {
        outputValues = outputValues.withStatusCode(ServerDefaults.StatusCodes.error)
      }
      setStatus(outputValues)(resp)
      forwardHeaders(outputValues)(resp)
      outputValues.body match {
        case Some(responseHandler)  => responseHandler(outputValues.contentLength)(rc)
        case None                   => resp.end()
      }
      logWhenHandled(resp.getStatusCode)
    } catch {
      case e: Throwable => rc.fail(e)
    }
  }

  private def formatToContentType(format: CodecFormat/*, maybeCharset: Option[Charset]*/): String =
    format.mediaType.toString // README: Charset doesn't seem to be accepted in tests

  private def forwardHeaders(outputValues: OutputValues[RoutingContextHandlerWithLength])
                            (resp: HttpServerResponse): Unit = {
    outputValues.headers.foreach { case (k,v) => resp.headers.add(k, v) }
    if (!resp.headers.contains(HttpHeaders.CONTENT_LENGTH.toString)) {
      outputValues.contentLength.foreach { length => resp.headers.add(HttpHeaders.CONTENT_LENGTH.toString, length.toString)}
    }
  }

  private def forwardHeaders(headers: Seq[Header], resp: HttpServerResponse): Unit =
    headers.foreach { h =>
      resp.headers.add(h.name, h.value)
    }

  private def setStatus[O](outputValues: OutputValues[O])(resp: HttpServerResponse): Unit =
    outputValues.statusCode.map(_.code).foreach(resp.setStatusCode)

  private val encodeOutputs: EncodeOutputs[RoutingContextHandlerWithLength] = new EncodeOutputs(
    new EncodeOutputBody[RoutingContextHandlerWithLength] {
      override def rawValueToBody(v: Any, format: CodecFormat, bodyType: RawBodyType[_]): RoutingContextHandlerWithLength = _ =>
        BodyEncoders(bodyType.asInstanceOf[RawBodyType[Any]], formatToContentType(format/*, charset(bodyType)*/), v)(_)
      override def streamValueToBody(v: Any, format: CodecFormat, charset: Option[Charset]): RoutingContextHandlerWithLength = contentLength =>
        StreamEncoders(formatToContentType(format/*, charset*/), v.asInstanceOf[ReadStream[Buffer]], contentLength)(_)
    }
  )

  private object BodyEncoders {
    def apply[CF <: CodecFormat, R](bodyType: RawBodyType[R], contentType: String, r: R): RoutingContextHandler = { rc =>
      val resp = rc.response
      if (resp.headers.get(HttpHeaders.CONTENT_TYPE.toString).isEmpty) {
        resp.putHeader(HttpHeaders.CONTENT_TYPE.toString, contentType)
      }
      handleFullBody(bodyType, r)(rc)
    }

    private def handleFullBody[R](bodyType: RawBodyType[R], r: R): RoutingContextHandler = { rc =>
      val resp = rc.response
      (bodyType match {
        case RawBodyType.StringBody(charset) => resp.end(r.toString, charset.toString)
        case RawBodyType.ByteArrayBody => resp.end(Buffer.buffer(r.asInstanceOf[Array[Byte]]))
        case RawBodyType.ByteBufferBody => resp.end(Buffer.buffer().setBytes(0, r.asInstanceOf[ByteBuffer]))
        case RawBodyType.InputStreamBody => resp.end(inputStreamToBuffer(r.asInstanceOf[InputStream]))
        case RawBodyType.FileBody => resp.sendFile(r.asInstanceOf[File].getPath)
        case m: RawBodyType.MultipartBody => handleMultipleBodyParts(m, r)(rc)
      }):Unit
    }

    private def handleMultipleBodyParts[CF <: CodecFormat, R](multipart: RawBodyType[R] with RawBodyType.MultipartBody, r: R): RoutingContextHandler = { rc =>
      val resp = rc.response
      resp.setChunked(true)
      resp.putHeader(HttpHeaders.CONTENT_TYPE.toString, "multipart/form-data")
      r.asInstanceOf[Seq[Part[_]]].foreach(handleBodyPart(multipart, _)(rc))
      if (!resp.ended) resp.end()
    }

    private def handleBodyPart[T](m: RawBodyType.MultipartBody, part: Part[T]): RoutingContextHandler = { rc =>
      val resp = rc.response
      m.partType(part.name).foreach { partType =>
        val partContentType = writePartHeaders(part)(resp)
        writeBodyPart(partType.asInstanceOf[RawBodyType[Any]], partContentType, part.body)(rc)
      }
    }

    private def writePartHeaders(part: Part[_] ): HttpServerResponse => String = { resp =>
      forwardHeaders(part.headers, resp)
      val partContentType = part.contentType.getOrElse("application/octet-stream")
      val dispositionParams = part.otherDispositionParams + (Part.NameDispositionParam -> part.name)
      val dispositionsHeaderParts = dispositionParams.map { case (k, v) => s"""$k="$v"""" }
      resp.write(s"${HttpHeaders.CONTENT_DISPOSITION}: form-data; ${dispositionsHeaderParts.mkString(", ")}")
      resp.write("\n")
      resp.write(s"${HttpHeaders.CONTENT_TYPE}: $partContentType")
      resp.write("\n\n")
      partContentType
    }

    private def writeBodyPart[CF <: CodecFormat, R](bodyType: RawBodyType[R], contentType: String, r: R): RoutingContextHandler = { rc =>
      val resp = rc.response
      resp.write(s"${HttpHeaders.CONTENT_TYPE}: $contentType")
      resp.write("\n")
      (bodyType match {
        case RawBodyType.StringBody(charset) => resp.write(r.toString, charset.toString)
        case RawBodyType.ByteArrayBody => resp.write(Buffer.buffer(r.asInstanceOf[Array[Byte]]))
        case RawBodyType.ByteBufferBody => resp.write(Buffer.buffer.setBytes(0, r.asInstanceOf[ByteBuffer]))
        case RawBodyType.InputStreamBody => resp.write(inputStreamToBuffer(r.asInstanceOf[InputStream]))
        case RawBodyType.FileBody =>
          val file = r.asInstanceOf[File]
          resp.write(s"""${HttpHeaders.CONTENT_DISPOSITION.toString}: file; file="${file.getName}"""")
          resp.write("\n")
          resp.write(rc.vertx.fileSystem.readFileBlocking(file.getAbsolutePath))
          resp.write("\n\n")
        case m: RawBodyType.MultipartBody => handleMultipleBodyParts(m, r)(rc)
      }): Unit
    }

  }

  private object StreamEncoders {
    def apply(contentType: String, stream: ReadStream[Buffer], contentLength: Option[Long]): RoutingContextHandler = { rc =>
      val resp = rc.response

      resp.putHeader(HttpHeaders.CONTENT_TYPE.toString, contentType)
      if (contentLength.isDefined) {
        resp.putHeader(HttpHeaders.CONTENT_LENGTH.toString, contentLength.get.toString)
      } else {
        resp.setChunked(true)
      }
      stream.pipeTo(resp): Unit
    }
  }

}