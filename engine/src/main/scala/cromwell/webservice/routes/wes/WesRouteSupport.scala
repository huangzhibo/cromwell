package cromwell.webservice.routes.wes

import akka.actor.ActorRef
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.{AskTimeoutException, ask}
import akka.util.Timeout
import cromwell.engine.instrumentation.HttpInstrumentation
import cromwell.services.metadata.MetadataService.{GetStatus, MetadataServiceResponse, StatusLookupFailed, StatusLookupResponse}
import cromwell.webservice.routes.CromwellApiService.{EnhancedThrowable, UnrecognizedWorkflowException, validateWorkflowId}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}
import WesResponseJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.directives.RouteDirectives.complete
import WesRouteSupport._
import cromwell.engine.workflow.WorkflowManagerActor.WorkflowNotFoundException
import cromwell.server.CromwellShutdown
import cromwell.webservice.routes.CromwellApiService

trait WesRouteSupport extends HttpInstrumentation {
  val serviceRegistryActor: ActorRef
  val workflowManagerActor: ActorRef
  val workflowStoreActor: ActorRef

  implicit val ec: ExecutionContext
  implicit val timeout: Timeout

  /*
    These routes are not currently going through the MetadataBuilderRegulator/MetadataBuilder. This is for two reasons:
      - It'd require a fairly substantial refactor of the MetadataBuilderActor to be more general
      - It's expected that for now the usage of these endpoints will not be extensive, so the protections of the regulator
         should not be necessary
    */
  val wesRoutes: Route =
    instrumentRequest {
      concat(
        pathPrefix("ga4gh" / "wes" / "v1" / "runs") {
          concat(
            path(Segment / "status") { possibleWorkflowId =>
              val response  = validateWorkflowId(possibleWorkflowId, serviceRegistryActor).flatMap(w => serviceRegistryActor.ask(GetStatus(w)).mapTo[MetadataServiceResponse])
              // WES can also return a 401 or a 403 but that requires user auth knowledge which Cromwell doesn't currently have
              onComplete(response) {
                case Success(s: StatusLookupResponse) =>
                  val wesState = WesState.fromCromwellStatus(s.status)
                  complete(WesRunStatus(s.workflowId.toString, wesState))
                case Success(r: StatusLookupFailed) => r.reason.errorRequest(StatusCodes.InternalServerError)
                case Success(m: MetadataServiceResponse) =>
                  // This should never happen, but ....
                  val error = new IllegalStateException("Unexpected response from Metadata service: " + m)
                  error.errorRequest(StatusCodes.InternalServerError)
                case Failure(_: UnrecognizedWorkflowException) => complete(NotFoundError)
                case Failure(e) => complete(WesErrorResponse(e.getMessage, StatusCodes.InternalServerError.intValue))
              }
            },
            path(Segment / "cancel") { possibleWorkflowId =>
              CromwellApiService.abortWorkflow(possibleWorkflowId, workflowStoreActor, workflowManagerActor, errorHandler = WesAbortErrorHandler)
            }
          )
        }
      )
    }
}

object WesRouteSupport {
  val NotFoundError = WesErrorResponse("The requested workflow run wasn't found", StatusCodes.NotFound.intValue)

  def WesAbortErrorHandler: PartialFunction[Throwable, Route] = {
    case e: IllegalStateException => e.errorRequest(StatusCodes.Forbidden)
    case e: WorkflowNotFoundException => e.errorRequest(StatusCodes.NotFound)
    case _: AskTimeoutException if CromwellShutdown.shutdownInProgress() => new Exception("Cromwell service is shutting down.").failRequest(StatusCodes.InternalServerError)
    case e: Exception => e.errorRequest(StatusCodes.InternalServerError)
  }
}