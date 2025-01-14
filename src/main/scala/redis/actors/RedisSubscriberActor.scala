package redis.actors

import akka.util.ByteString
import redis.protocol.Error
import redis.protocol.MultiBulk
import redis.protocol.RedisReply
import redis.api.pubsub._
import java.net.InetSocketAddress
import redis.api.connection.Auth

class RedisSubscriberActorWithCallback(
  address: InetSocketAddress,
  channels: Seq[String],
  patterns: Seq[String],
  messageCallback: Message => Unit,
  pmessageCallback: PMessage => Unit,
  authUsername: Option[String] = None,
  authPassword: Option[String] = None,
  onConnectStatus: Boolean => Unit
) extends RedisSubscriberActor(address, channels, patterns, authUsername, authPassword, onConnectStatus) {
  def onMessage(m: Message) = messageCallback(m)

  def onPMessage(pm: PMessage) = pmessageCallback(pm)
}

abstract class RedisSubscriberActor(
  address: InetSocketAddress,
  channels: Seq[String],
  patterns: Seq[String],
  authUsername: Option[String] = None,
  authPassword: Option[String] = None,
  onConnectStatus: Boolean => Unit
) extends RedisWorkerIO(address, onConnectStatus)
    with DecodeReplies {
  def onConnectWrite(): ByteString = {
    (authUsername, authPassword) match {
      case (Some(username), Some(password)) => Auth(username, Some(password)).encodedRequest
      case (None, Some(password)) => Auth(password).encodedRequest
      case (_, _) => ByteString.empty
    }
  }

  def onMessage(m: Message): Unit

  def onPMessage(pm: PMessage): Unit

  /**
   * Keep states of channels and actor in case of connection reset
   */
  var channelsSubscribed = channels.toSet
  var patternsSubscribed = patterns.toSet

  override def preStart(): Unit = {
    super.preStart()
    if (channelsSubscribed.nonEmpty) {
      write(SUBSCRIBE(channelsSubscribed.toSeq: _*).toByteString)
    }
    if (patternsSubscribed.nonEmpty) {
      write(PSUBSCRIBE(patternsSubscribed.toSeq: _*).toByteString)
    }
  }

  def writing: Receive = { case message: SubscribeMessage =>
    if (message.params.nonEmpty) {
      write(message.toByteString)
      message match {
        case s: SUBSCRIBE => channelsSubscribed ++= s.channel
        case u: UNSUBSCRIBE => channelsSubscribed --= u.channel
        case ps: PSUBSCRIBE => patternsSubscribed ++= ps.pattern
        case pu: PUNSUBSCRIBE => patternsSubscribed --= pu.pattern
      }
    }
  }

  def subscribe(channels: String*): Unit = {
    self ! SUBSCRIBE(channels: _*)
  }

  def unsubscribe(channels: String*): Unit = {
    self ! UNSUBSCRIBE(channels: _*)
  }

  def psubscribe(patterns: String*): Unit = {
    self ! PSUBSCRIBE(patterns: _*)
  }

  def punsubscribe(patterns: String*): Unit = {
    self ! PUNSUBSCRIBE(patterns: _*)
  }

  def onConnectionClosed(): Unit = {}

  def onWriteSent(): Unit = {}

  def onDataReceived(dataByteString: ByteString): Unit = {
    decodeReplies(dataByteString)
  }

  def onDecodedReply(reply: RedisReply): Unit = {
    reply match {
      case MultiBulk(Some(list)) if list.length == 3 && list.head.toByteString.utf8String == "message" =>
        onMessage(Message(list(1).toByteString.utf8String, list(2).toByteString))
      case MultiBulk(Some(list)) if list.length == 4 && list.head.toByteString.utf8String == "pmessage" =>
        onPMessage(PMessage(list(1).toByteString.utf8String, list(2).toByteString.utf8String, list(3).toByteString))
      case error @ Error(_) =>
        onErrorReply(error)
      case _ => // subscribe or psubscribe
    }
  }

  def onDataReceivedOnClosingConnection(dataByteString: ByteString): Unit = decodeReplies(dataByteString)

  def onClosingConnectionClosed(): Unit = {}

  def onErrorReply(error: Error): Unit = {}
}
