package cromwell.services.instrumentation.impl.selectivetsv

import java.io.File
import java.time.OffsetDateTime
import java.util.UUID

import akka.actor.{Actor, ActorRef, Props}
import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import cromwell.services.instrumentation.{CromwellBucket, CromwellGauge, CromwellIncrement}
import cromwell.services.instrumentation.InstrumentationService.InstrumentationServiceMessage
import cromwell.services.instrumentation.impl.selectivetsv.SelectiveTsvInstrumentationServiceActor.{SnapshotState, StateHistory}
import cromwell.util.GracefulShutdownHelper.ShutdownCommand

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

/**
  * Actor that ignores every InstrumentationServiceMessage - This is the default implementation of this service
  */
class SelectiveTsvInstrumentationServiceActor(serviceConfig: Config, globalConfig: Config, serviceRegistryActor: ActorRef)
  extends Actor
  with StrictLogging {

  implicit val ec: ExecutionContext = context.dispatcher
  context.system.scheduler.schedule(10.seconds, 1.seconds) { self ! SnapshotState }

  var stateHistory: StateHistory = StateHistory.empty

  override def receive: Receive = {
    case InstrumentationServiceMessage(CromwellIncrement(CromwellBucket(_, path))) =>
      val pathString = path.init.mkString(".")

      if (path.last == "starting") {
        stateHistory = stateHistory.increment(pathString)
      } else if (path.last == "done") {
        stateHistory = stateHistory.decrement(pathString)
      }

    case InstrumentationServiceMessage(CromwellGauge(CromwellBucket(_, path), value)) =>
      val pathString = path.init.mkString(".")
      if (path.last == "set") {
        stateHistory = stateHistory.set(pathString, value)
      }

    case SnapshotState =>
      stateHistory = stateHistory.snapshotState()
      if (checkTsvPrintout()) {
        stateHistory = StateHistory.empty
        logger.info("Resetting state history")
      }
//      logger.info(stateHistory.currentStateTsv())

    case ShutdownCommand => context stop self

//    case other: InstrumentationServiceMessage => println(s"Ignoring $other")
  }

  private def checkTsvPrintout(): Boolean = {
    if (stateHistory.stateHistory.size >= 2) {
      if (stateHistory.stateHistory.last._2.get("jobs.ejea.executing").contains(0)) {
        if (stateHistory.stateHistory.init.last._2.get("jobs.ejea.executing").exists(_ != 0)) {
          outputCountHistory()
          true
        } else false
      } else false
    } else false
  }

  private def outputCountHistory(): Unit = {
    import java.io.BufferedWriter
    import java.io.FileOutputStream
    import java.io.OutputStreamWriter
    val fout = new File(s"selective-tsv-timestamps-${UUID.randomUUID().toString}.tsv")
    val fos = new FileOutputStream(fout)

    val bw = new BufferedWriter(new OutputStreamWriter(fos))

    for (line <- stateHistory.stateHistoryTsv()) {
      bw.write(line)
      bw.newLine()
    }
    bw.flush()
    bw.close()
    logger.info(s"Printed out timestamps to ${fout.getAbsolutePath}")
  }
}

object SelectiveTsvInstrumentationServiceActor {
  def props(serviceConfig: Config, globalConfig: Config, serviceRegistryActor: ActorRef): Props =
    Props(new SelectiveTsvInstrumentationServiceActor(serviceConfig, globalConfig, serviceRegistryActor))

  case object SnapshotState

  final case class StateHistory(fields: Vector[String], currentState: Map[String, Int], stateHistory: Vector[(OffsetDateTime, Map[String, Int])]) {
    def increment(field: String): StateHistory = {
      val newState = currentState + (field -> (currentState.getOrElse(field, 0) + 1))
      if (fields.contains(field)) {
        this.copy(
          currentState = newState
        )
      } else {
        this.copy(
          fields = fields :+ field,
          currentState = newState
        )
      }
    }
    def decrement(field: String): StateHistory = {
      this.copy(
        currentState = currentState + (field -> (currentState(field) - 1))
      )
    }

    def set(field: String, value: Long): StateHistory = {
      if (fields.contains(field)) {
        this.copy(
          currentState = currentState + (field -> value.intValue)
        )
      } else {
        this.copy(
          fields = fields :+ field,
          currentState = currentState + (field -> value.intValue)
        )
      }
    }

    def snapshotState(): StateHistory = {
      if (this == StateHistory.empty) this
      else this.copy(
        stateHistory = stateHistory :+ (OffsetDateTime.now -> currentState)
      )
    }

    def stateHistoryTsv(): Vector[String] = {

      val interestingFields = fields.filter(field => stateHistory.exists(history => history._2.get(field).exists(_ != 0)))

      val header = (List("timestamp") ++ interestingFields).mkString("\t")
      val rows = stateHistory.map { case (timestamp, fieldMap) =>
        (Vector(timestamp.toString) ++ interestingFields.map(f => fieldMap.getOrElse(f, 0))).mkString("\t")
      }
      Vector(header) ++ rows
    }
  }

  object StateHistory {
    val empty: StateHistory = StateHistory(Vector.empty, Map.empty, Vector.empty)
  }
}
