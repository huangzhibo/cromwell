package cromwell.webservice.routes.wes

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import cromwell.core._
import spray.json.{DefaultJsonProtocol, JsString, JsValue, RootJsonFormat}

sealed trait WesState extends Product with Serializable
case object UNKNOWN extends WesState
case object QUEUED extends WesState
case object INITIALIZING extends WesState
case object RUNNING extends WesState
case object PAUSED extends WesState
case object COMPLETE extends WesState
case object EXECUTOR_ERROR extends WesState
case object SYSTEM_ERROR extends WesState
case object CANCELED extends WesState
case object CANCELING extends WesState // Before any shorebirds worry about the spelling, it really is CANCELING

object WesState {
  def fromCromwellStatus(cromwellStatus: WorkflowState): WesState = {
    cromwellStatus match {
      case WorkflowOnHold => PAUSED
      case WorkflowSubmitted => QUEUED
      case WorkflowRunning => RUNNING
      case WorkflowAborting => CANCELING
      case WorkflowAborted => CANCELED
      case WorkflowSucceeded => COMPLETE
      case WorkflowFailed => EXECUTOR_ERROR
      case _ => UNKNOWN
    }
  }
}

object WesStateJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit object WesStateFormat extends RootJsonFormat[WesState] {
    def write(obj: WesState): JsValue = JsString(obj.toString)

    def read(json: JsValue): WesState = throw new UnsupportedOperationException("Reading WesState unsupported")
  }
}