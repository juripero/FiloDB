package filodb.kafka

import java.lang.{Long => JLong}

import scala.concurrent.duration._
import scala.concurrent.Await

import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.StrictLogging
import monix.execution.{Ack, Scheduler}
import monix.execution.Ack._
import monix.eval.Task
import monix.reactive.{Consumer, Observable, Observer}
import org.apache.kafka.clients.producer.ProducerRecord

import filodb.coordinator.IngestionCommands.DatasetSetup
import filodb.coordinator.IngestionStreamFactory
import filodb.coordinator.NodeClusterActor.IngestionSource
import filodb.core.memstore.{IngestRecord, TimeSeriesMemStore}
import filodb.core.metadata.{Column, DataColumn, Dataset, RichProjection}
import org.velvia.filo.{RoutingRowReader, TupleRowReader}

/** Start Zookeeper and Kafka.
  * Run ./bin/kafka-topics.sh --create --zookeeper localhost:2181 --replication-factor 1 --partitions 2 --topic integration-test-topic
  * Make sure the configured settings for number of partitions and topic name below match what you created.
  */
class KafkaIngestionStreamSuite extends ConfigSpec with StrictLogging {

  import filodb.core._
  import Column.ColumnType._

  private val count = 1000

  private val config = ConfigFactory.parseString(
    s"""
       |filodb.kafka.topics.ingestion="integration-test-topic7"
       |filodb.kafka.partitions=2
       |filodb.kafka.record-converter="${classOf[PartitionRecordConverter].getName}"
        """.stripMargin)

  implicit val timeout: Timeout = 10.seconds

  implicit val ec = scala.concurrent.ExecutionContext.global

  "IngestionStreamFactory" must {
    "create a new KafkaStream" in {

      // data:
      val schema = Seq(DataColumn(0, "timestamp", "timestamp", 0, StringColumn))
      val dataset = Dataset("metrics", "timestamp", ":string 0")
      val datasetRef = DatasetRef(dataset.name)
      val projection = RichProjection(dataset, schema)
      val source = IngestionSource(classOf[KafkaIngestionStreamFactory].getName)
      val ds = DatasetSetup(dataset, schema.map(_.toString), 0, source)

      // kafka config
      val settings = new KafkaSettings(config)

      // coordinator:
      val ctor = Class.forName(ds.source.streamFactoryClass).getConstructors.head
      val memStore = new TimeSeriesMemStore(settings.config.getConfig("filodb"))
      memStore.setup(projection, 0)
      memStore.reset()

      // a completed-aware observer showing a consumer of the streams
      val coordinator = Consumer.fromObserver[Seq[IngestRecord]] { implicit scheduler =>
        val range = Range(0, count-1).map(_.toString)

        new Observer.Sync[Seq[IngestRecord]] {
          def onNext(elem: Seq[IngestRecord]): Ack = {
            elem.headOption.collect {
              case IngestRecord(_, RoutingRowReader(TupleRowReader((m: String,p: Int)), _), o) if m == range.max =>
                logger.debug(s"Processing last published event for partition $p with offset $o")
                Consumer.complete
                Stop
              case IngestRecord(_, RoutingRowReader(TupleRowReader((m: String,p: Int)), _), o) =>
                Continue
            }.getOrElse(Continue)
          }
          def onComplete(): Unit = logger.debug("task completed")
          def onError(e: Throwable): Unit = logger.error("task error", e)
        }
      }

      // producer:
      implicit val io = Scheduler.io("filodb-kafka-tests")
      val producer = PartitionedProducerSink.create[JLong, String](settings, io)

      val tasks = for (partition <- 0 until settings.NumPartitions) yield {
        // The producer task creates `count` ProducerRecords, each range divided equally between the topic's partitions
        val sinkT = Observable.range(0, count)
          .map(msg => new ProducerRecord[JLong, String](settings.IngestionTopic, JLong.valueOf(partition), msg.toString))
          .bufferIntrospective(1024)
          .consumeWith(producer)

        // The consumer task creates one ingestion stream per topic-partition (consumer.assign(topic,partition)
        // this is currently a 1:1 Observable stream
        val  sourceT = {
          val streamFactory = ctor.newInstance().asInstanceOf[IngestionStreamFactory]
          streamFactory.isInstanceOf[KafkaIngestionStreamFactory] must be(true)
          streamFactory
            .create(settings.config, projection, partition)
            .get
            .consumeWith(coordinator)
        }
        Task.zip2(Task.fork(sourceT), Task.fork(sinkT)).runAsync
      }

      tasks foreach (task => Await.result(task, 60.seconds))
    }
  }
}


final class PartitionRecordConverter extends RecordConverter {

  override def convert(proj: RichProjection, event: AnyRef, partition: Int, offset: Long): Seq[IngestRecord] =
    Seq(IngestRecord(proj, TupleRowReader((event, partition)), offset))

}
