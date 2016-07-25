package zipkin.finagle.kafka

import java.net.InetSocketAddress
import java.util.Properties

import com.twitter.conversions.storage._
import com.twitter.conversions.time._
import com.twitter.finagle.stats.{DefaultStatsReceiver, NullStatsReceiver, StatsReceiver}
import com.twitter.finagle.tracing.Tracer
import com.twitter.finagle.zipkin.core.SamplingTracer
import com.twitter.util.{Duration, StorageUnit}
import org.apache.kafka.clients.producer.{KafkaProducer, Producer}
import zipkin.{initialSampleRate => sampleRateFlag}
import zipkin.kafka.{bootstrapServers => bootstrapServersFlag, topic => topicFlag}

object KafkaZipkinTracer {
  lazy val default: Tracer = mk()

  /**
   * @param bootstrapServers initial set of kafka brokers to connect to, rest of the cluster will be discovered
   * @param topic kafka topic the traces will be sent to
   * @param statsReceiver Where to log information about tracing success/failures
   * @param sampleRate How much data to collect. Default sample rate 0.1%. Max is 1, min 0.
   */
  def mk(
    bootstrapServers: Seq[InetSocketAddress] = bootstrapServersFlag(),
    topic: String = topicFlag(),
    statsReceiver: StatsReceiver = NullStatsReceiver,
    sampleRate: Float = sampleRateFlag()
  ): KafkaZipkinTracer =
    new KafkaZipkinTracer(
      new KafkaRawZipkinTracer(
        KafkaZipkinTracer.newProducer(bootstrapServers), topic, statsReceiver
      ), sampleRate)

  /**
   * Util method since named parameters can't be called from Java
   * @param statsReceiver Where to log information about tracing success/failures
   */
  def mk(statsReceiver: StatsReceiver): Tracer =
    mk(bootstrapServers = bootstrapServersFlag(), statsReceiver = statsReceiver, sampleRate = sampleRateFlag())

  /**
   * @param bootstrapServers initial set of kafka brokers to connect to
   * @param maxBufferSize max buffer size for the kafka producer
   */
  private[kafka] def newProducer(
    bootstrapServers: Seq[InetSocketAddress],
    maxBufferSize: StorageUnit = 5.megabytes,
    requestTimeout: Duration = 1.second
  ): Producer[Array[Byte], Array[Byte]] = {
    val kafkaBootstrapServers = bootstrapServers.map(addr =>  s"${addr.getHostName}:${addr.getPort}").mkString(",")
    val props: Properties = new Properties
    props.put("bootstrap.servers", kafkaBootstrapServers) // Initial brokers to connect to, rest of the cluster will be discovered
    props.put("key.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer")
    props.put("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer")
    props.put("acks", "1") // Ack after immediately writing to the leader
    props.put("buffer.memory", maxBufferSize.inBytes: java.lang.Long)
    props.put("block.on.buffer.full", false: java.lang.Boolean) // Throw errors when buffer is full
    props.put("max.block.ms", 0: java.lang.Integer) // Do not block on Producer#send
    props.put("retries", 0: java.lang.Integer)
    props.put("request.timeout.ms", requestTimeout.inMillis.toInt: java.lang.Integer)
    new KafkaProducer[Array[Byte], Array[Byte]](props)
  }
}

class KafkaZipkinTracer(
  underlying: KafkaRawZipkinTracer,
  sampleRate: Float
) extends SamplingTracer(underlying, sampleRate) {
  /**
   * Default constructor for the service loader
   */
  def this() = this(
    new KafkaRawZipkinTracer(
      producer = KafkaZipkinTracer.newProducer(bootstrapServersFlag()),
      topic = topicFlag(),
      statsReceiver = DefaultStatsReceiver.scope("zipkin.kafka")
    ), sampleRateFlag())
}