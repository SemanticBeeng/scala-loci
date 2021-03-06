package loci
package ws.akka

import network.Connection
import util.Notifier
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.model.ws.TextMessage
import scala.util.Success
import scala.util.Failure
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.collection.mutable.Queue
import java.util.concurrent.atomic.AtomicBoolean

private object WSConnectionHandler {
  def handleWebSocket(
      protocolInfo: Future[WS],
      connectionEstablished: Connection => Unit,
      connectionFailed: Throwable => Unit)
    (implicit
      materializer: Materializer) = {

    new WSAbstractConnectionHandler[Message] {
      def createMessage(data: String) = TextMessage(data)

      def processMessage(message: Message) = message match {
        case TextMessage.Strict(data) =>
          Future successful data

        case message: TextMessage =>
          implicit val context = this.context

          message.textStream.runFold(new StringBuilder) {
            case (builder, data) =>
              builder append data
          } map { _.toString }

        case _ =>
          Future failed new UnsupportedOperationException(
            s"Unsupported type of message: $message")
      }
    } handleWebSocket (protocolInfo, connectionEstablished, connectionFailed)
  }
}

private abstract class WSAbstractConnectionHandler[M] {
  val context = contexts.Queued.create

  def createMessage(data: String): M

  def processMessage(message: M): Future[String]

  def handleWebSocket(
      protocolInfo: Future[WS],
      connectionEstablished: Connection => Unit,
      connectionFailed: Throwable => Unit) = {
    implicit val context = this.context

    // keep alive

    val delay = 20.seconds
    val timeout = 40.seconds


    // connection interface

    val promises = Queue.empty[Promise[Option[(Unit, M)]]]
    val open = new AtomicBoolean(true)
    val doClosed = Notifier[Unit]
    val doReceive = Notifier[String]

    def connectionOpen = open.get

    def connectionSend(data: String) = promises synchronized {
      if (connectionOpen) {
        val message = Some(((), createMessage("#" + data)))
        if (!promises.isEmpty && !promises.head.isCompleted)
          promises.dequeue success message
        else
          promises enqueue (Promise successful message)
      }
    }

    def connectionClose() = promises synchronized {
      if (connectionOpen) {
        open set false
        promises foreach { _ trySuccess None }
        promises.clear
        doClosed()
      }
    }

    protocolInfo foreach { protocolInfo =>
      promises synchronized {
        val connection = new Connection {
          val protocol = protocolInfo
          val closed = doClosed.notification
          val receive = doReceive.notification

          def isOpen = connectionOpen
          def send(data: String) = connectionSend(data)
          def close() = connectionClose
        }

        connectionEstablished(connection)
      }
    }


    // source, sink and flow

    val source = Source.unfoldAsync(()) { _ =>
      promises synchronized {
        if (connectionOpen) {
          if (promises.isEmpty) {
            val promise = Promise[Option[(Unit, M)]]
            promises enqueue promise
            promise.future
          }
          else
            promises.dequeue.future
        }
        else
          Future successful None
      }
    }

    val sink = Sink foreach[M] {
      processMessage(_) onComplete {
        case Success(data) =>
          if (data startsWith "#")
            doReceive(data substring 1)

        case Failure(_) =>
          connectionClose
      }
    }


    // flow

    val flow = Flow.fromSinkAndSourceMat(sink, source) { (future, _) =>
      future onComplete { _ => connectionClose }
    }

    def keepAliveMessage() = createMessage("!")

    (Flow[M]
      idleTimeout timeout
      via flow
      keepAlive (delay, keepAliveMessage))
  }
}
