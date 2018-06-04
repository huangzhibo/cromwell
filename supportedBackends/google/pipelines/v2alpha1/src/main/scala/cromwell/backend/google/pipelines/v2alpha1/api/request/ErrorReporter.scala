package cromwell.backend.google.pipelines.v2alpha1.api.request

import cats.data.Validated.{Invalid, Valid}
import com.google.api.services.genomics.v2alpha1.model._
import common.validation.ErrorOr.ErrorOr
import common.validation.Validation._
import cromwell.backend.google.pipelines.common.api.RunStatus.{Cancelled, Failed, Preempted}
import cromwell.backend.google.pipelines.v2alpha1.api.ActionBuilder.Labels.Key
import cromwell.backend.google.pipelines.v2alpha1.api.Deserialization._
import cromwell.backend.google.pipelines.v2alpha1.api.request.RequestHandler.logger
import cromwell.core.{ExecutionEvent, WorkflowId}
import io.grpc.{Status => GStatus}
import mouse.all._

import scala.collection.JavaConverters._

object ErrorReporter {
  // This can be used to log non-critical deserialization failures and not fail the task
  implicit class ErrorOrLogger[A](val t: ErrorOr[A]) extends AnyVal {
    private def errorMessage(workflowId: WorkflowId, operation: Operation, exception: Option[Throwable] = None) =
      s"[$workflowId] Failed to parse PAPI response. Operation Id: ${operation.getName}"
    
    def fallBack(implicit workflowId: WorkflowId, operation: Operation): Option[A] = t match {
      case Valid(s) => Option(s)
      case Invalid(f) =>
        logger.error(errorMessage(workflowId, operation) + s" ${f.toList.mkString(", ")}")
        None
    }

    def fallBackTo(to: A)(implicit workflowId: WorkflowId, operation: Operation): A = t match {
      case Valid(s) => s
      case Invalid(f) =>
        logger.error(s"[$workflowId] Failed to parse PAPI response. Operation Id: ${operation.getName}. ${f.toList.mkString(", ")}")
        to
    }
  }
}

class ErrorReporter(machineType: Option[String],
                    wasPreemptible: Boolean,
                    executionEvents: List[ExecutionEvent],
                    zone: Option[String],
                    instanceName: Option[String],
                    actions: List[Action])(implicit operation: Operation, workflowId: WorkflowId) {
  import ErrorReporter._

  def toUnsuccessfulRunStatus(error: Status, events: List[Event]) = {
    val status = GStatus.fromCodeValue(error.getCode)
    val builder = status match {
      case GStatus.UNAVAILABLE if wasPreemptible => Preempted.apply _
      case GStatus.CANCELLED => Cancelled.apply _
      case _ => Failed.apply _
    }

    val failed: Option[String] = summaryFailure(events)
    // Reverse the list because the first failure (likely the most relevant, will appear last otherwise)
    val unexpectedExitEvents: List[String] = unexpectedExitStatusErrorStrings(events, actions).reverse

    builder(status, None, failed.toList ++ unexpectedExitEvents, executionEvents, machineType, zone, instanceName)
  }

  // There's maybe one FailedEvent per operation with a summary error message
  private def unexpectedExitStatusErrorStrings(events: List[Event], actions: List[Action]): List[String] = {
    for {
      event <- events
      detail <- event.details[UnexpectedExitStatusEvent].flatMap(_.toErrorOr.fallBack)
      stderr = detail.getActionId |> stderrForAction(events)
      // Try to find the action and its tag label. -1 because action ids are 1-based
      action = actions.lift(detail.getActionId.toInt - 1)
      labelTag = action.flatMap(actionLabelTag)
      inputNameTag = action.flatMap(actionLabelInputName)
    } yield unexpectedStatusErrorString(event, stderr, labelTag, inputNameTag)
  }

  // It would probably be good to define a richer error structure than String, but right now that's what the backend interface expects
  private def unexpectedStatusErrorString(event: Event, stderr: Option[String], labelTag: Option[String], inputNameTag: Option[String]) = {
    labelTag.map("[" + _ + "] ").getOrElse("") +
      inputNameTag.map("Input name: " +  _ + " - ").getOrElse("") +
      event.getDescription +
      stderr.map(": " + _).getOrElse("")
  }

  // There may be one FailedEvent per operation with a summary error message
  private def summaryFailure(events: List[Event]): Option[String] = {
    findEvent[FailedEvent](events).map(_.getCause)
  }

  // Try to find the stderr for the given action ID
  private def stderrForAction(events: List[Event])(actionId: Integer) = {
    findEvent[ContainerStoppedEvent](events, _.getActionId == actionId).map(_.getStderr)
  }

  private def actionLabelValue(action: Action, k: String) = action.getLabels.asScala.get(k)
  private def actionLabelTag(action: Action) = actionLabelValue(action, Key.Tag)
  private def actionLabelInputName(action: Action) = actionLabelValue(action, Key.InputName)
}
