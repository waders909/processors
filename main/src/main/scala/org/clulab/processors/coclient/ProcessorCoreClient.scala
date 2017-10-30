package org.clulab.processors.coclient

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.postfixOps

import com.typesafe.config.{ Config, ConfigValueFactory, ConfigFactory }
import com.typesafe.scalalogging.LazyLogging

import akka.actor._
import akka.pattern.ask
import akka.routing.Broadcast
import akka.util.Timeout

import org.clulab.processors._
import org.clulab.processors.coshare.ProcessorCoreMessages._

/**
  * Client to access the Processors Core Server remotely using Akka.
  *   Written by: Tom Hicks. 6/9/2017.
  *   Last Modified: Update to implement processor annotator trait only.
  */
object ProcessorCoreClient extends LazyLogging {

  // THE instance of the the processor core client
  private var _pcc: ProcessorCoreClient = _

  /** Create a single instance of the processor core client, only if it has not been created. */
  def instance: ProcessorCoreClient = {
    logger.debug(s"(ProcessorCoreClient.instance): pcc = ${_pcc}")
    if (_pcc == null) {                     // create client, iff not already created
      val config = ConfigFactory.load().getConfig("ProcessorCoreClient")
      if (config == null)
        throw new RuntimeException("(ProcessorCoreClient): Unable to read configuration from configuration file.")
      _pcc = new ProcessorCoreClient(config)
    }
    logger.debug(s"(ProcessorCoreClient.instance): pcc => ${_pcc}")
    _pcc
  }

  /** Expose the shutdown method from the instance. */
  def shutdown: Unit = instance.shutdown
}


class ProcessorCoreClient (

  /** Application-specific portion of the configuration file. */
  val config: Config

) extends ProcessorAnnotator with LazyLogging {

  private val connectTime = 30.seconds

  logger.debug(s"(ProcessorCoreClient): config=${config}")

  // fire up the actor system
  val system = ActorSystem("procCoreClient", config)
  logger.debug(s"(ProcessorCoreClient): system=${system}")

  // simulate blocking RPC: finite duration is required so make it long
  implicit val timeout = Timeout(8 hours)  // time limit to return Future from call

  // fire up the processor core server and get a ref to the message router
  val router: ActorRef = getRouterRef(config)

  /** Acquire actor ref via actor selection on the configured server path. */
  private def getRouterRef (config: Config): ActorRef = {
    val serverPath = if (config.hasPath("server.path"))
      config.getString("server.path")
    else
      throw new RuntimeException("(ProcessorCoreClient): Configuration file must define server.path")
    val ref = system.actorSelection(ActorPath.fromString(serverPath)).resolveOne(connectTime)
    Await.result(ref, connectTime).asInstanceOf[ActorRef]
  }

  /** Send the given message to the server and block until response comes back. */
  private def callServer (request: ProcessorCoreCommand): ProcessorCoreReply = {
    val response = router ? request         // call returns Future within long timeout
    val result = Await.result(response, Duration.Inf) // blocking: wait forever
    if (result.isInstanceOf[ServerExceptionMsg]) {
      val exception = result.asInstanceOf[ServerExceptionMsg].exception
      throw new RuntimeException(exception)
    }
    else
      result.asInstanceOf[ProcessorCoreReply]
  }

  /** Send the core server a message to shutdown actors and terminate the actor system. */
  def shutdown: Unit = {
    if (config.getBoolean("shutdownServerOnExit")) {
      router ! Broadcast(PoisonPill)
      router ! PoisonPill
    }
  }


  /** Annotate the given text string, specify whether to retain the text in the resultant Document. */
  override def annotate (text:String, keepText:Boolean = false): Document = {
    val reply = callServer(AnnotateTextCmd(text, keepText))
    reply.asInstanceOf[DocumentMsg].doc
  }

  /** Annotate the given sentences, specify whether to retain the text in the resultant Document. */
  override def annotateFromSentences (
    sentences:Iterable[String],
    keepText:Boolean = false): Document =
  {
    val reply = callServer(AnnotateFromSentencesCmd(sentences, keepText))
    reply.asInstanceOf[DocumentMsg].doc
  }

  /** Annotate the given tokens, specify whether to retain the text in the resultant Document. */
  override def annotateFromTokens (
    sentences:Iterable[Iterable[String]],
    keepText:Boolean = false
  ): Document = {
    val reply = callServer(AnnotateFromTokensCmd(sentences, keepText))
    reply.asInstanceOf[DocumentMsg].doc
  }

  // Only for error testing -- should not be exposed as part of API
  // def errorTest: Unit = {
  //   callServer(ErrorTestCmd())              // should throw RuntimeException
  // }

}
