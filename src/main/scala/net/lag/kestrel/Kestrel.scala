/*
 * Copyright 2009 Twitter, Inc.
 * Copyright 2009 Robey Pointer <robeypointer@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.lag.kestrel

import java.net.InetSocketAddress
import java.util.concurrent.{CountDownLatch, Executors, ExecutorService, TimeUnit}
import java.util.{Timer, TimerTask}
import com.twitter.actors.{Actor, Scheduler}
import com.twitter.actors.Actor._
import scala.collection.mutable
import com.twitter.util.{Eval, Time}
import org.apache.mina.core.session.IoSession
import org.apache.mina.filter.codec.ProtocolCodecFilter
import org.apache.mina.transport.socket.SocketAcceptor
import org.apache.mina.transport.socket.nio.{NioProcessor, NioSocketAcceptor}
import _root_.net.lag.configgy.{Config, ConfigMap, Configgy, RuntimeEnvironment}
import _root_.net.lag.logging.Logger
import _root_.net.lag.naggati.IoHandlerActorAdapter


object KestrelStats {
  val bytesRead = new Counter
  val bytesWritten = new Counter
  val sessions = new Counter
  val totalConnections = new Counter
  val getRequests = new Counter
  val setRequests = new Counter
  val peekRequests = new Counter
  val sessionID = new Counter
}


object Kestrel {
  private val log = Logger.get
  val runtime = new RuntimeEnvironment(getClass)

  var queues: QueueCollection = null

  private val _expiryStats = new mutable.HashMap[String, Int]
  private val _startTime = Time.now.inMilliseconds

  var acceptorExecutor: ExecutorService = null
  var acceptor: SocketAcceptor = null

  private val deathSwitch = new CountDownLatch(1)

  val DEFAULT_PORT = 22133


  def main(args: Array[String]): Unit = {
    runtime.load(args)
    val config = Eval[kestrel.config.Kestrel](runtime.configFilename)
    startup(config)
  }

  def startup(config: kestrel.config.Kestrel): Unit = {
    // this one is used by the actor initialization, so can only be set at startup.
    var maxThreads = config.maxThreads

    /* If we don't set this to at least 4, we get an IllegalArgumentException when constructing
     * the ThreadPoolExecutor from inside FJTaskScheduler2 on a single-processor box.
     */
    if (maxThreads < 4) {
      maxThreads = 4
    }

    System.setProperty("actors.maxPoolSize", maxThreads.toString)
    log.debug("max_threads=%d", maxThreads)

    PersistentQueue.config = config
    val listenAddress = config.listenAddress
    val listenPort = config.port
    queues = new QueueCollection(config.queuePath, config.queues)
//    config.subscribe { c => configure(c.getOrElse(new Config)) }

    queues.loadQueues()

    acceptorExecutor = Executors.newCachedThreadPool()
    acceptor = new NioSocketAcceptor(acceptorExecutor, new NioProcessor(acceptorExecutor))

    // mina setup:
    acceptor.setBacklog(1000)
    acceptor.setReuseAddress(true)
    acceptor.getSessionConfig.setTcpNoDelay(true)
    val protocolCodec = config.protocol
    acceptor.getFilterChain.addLast("codec", new ProtocolCodecFilter(
      memcache.Codec.encoderFor(protocolCodec),
      memcache.Codec.decoderFor(protocolCodec)))
    acceptor.setHandler(new IoHandlerActorAdapter(session => new KestrelHandler(session, config.timeout)))
    acceptor.bind(new InetSocketAddress(listenAddress, listenPort))

    // expose config thru JMX.
    //config.registerWithJmx("net.lag.kestrel")

    // optionally, start a periodic timer to clean out expired items.
    val expirationTimerFrequency = config.expirationTimerFrequency.inSeconds
    if (expirationTimerFrequency > 0) {
      val timer = new Timer("Expiration timer", true)
      val expirationTask = new TimerTask {
        def run() {
          val expired = Kestrel.queues.flushAllExpired()
          if (expired > 0) {
            log.info("Expired %d item(s) from queues automatically.", expired)
          }
        }
      }
      timer.schedule(expirationTask, expirationTimerFrequency * 1000, expirationTimerFrequency * 1000)
    }

    log.info("Kestrel started.")

    // make sure there's always one actor running so scala 2.7.2 doesn't kill off the actors library.
    actor {
      deathSwitch.await
    }
  }

  def shutdown(): Unit = {
    log.info("Shutting down!")
    queues.shutdown
    acceptor.unbind
    acceptor.dispose
    Scheduler.shutdown
    acceptorExecutor.shutdown
    // the line below causes a 1 second pause in unit tests. :(
    //acceptorExecutor.awaitTermination(5, TimeUnit.SECONDS)
    deathSwitch.countDown
  }

  def uptime() = (Time.now.inMilliseconds - _startTime) / 1000
}
