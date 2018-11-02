package cromwell.webservice.routes.wes

import akka.actor.ActorRef
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.util.Timeout
import cromwell.engine.instrumentation.HttpInstrumentation
import cromwell.services.metadata.MetadataService.{GetStatus, MetadataServiceResponse, StatusLookupResponse}
import cromwell.webservice.routes.CromwellApiService.{EnhancedThrowable, UnrecognizedWorkflowException, validateWorkflowId}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}
import WesResponseJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.directives.RouteDirectives.complete
import cromwell.webservice.metadata.MetadataBuilderActor.FailedMetadataResponse
import WesRouteSupport._

trait WesRouteSupport extends HttpInstrumentation {
  val serviceRegistryActor: ActorRef

  implicit val ec: ExecutionContext
  implicit val timeout: Timeout

  /*
    These routes are not currently going through the MetadataBuilderRegulator/MetadataBuilder. This is for two reasons:
        - It'd require a fairly substantial refactor of the MetadataBuilderActor to be more general
        - It's expected that for now the usage of these endpoints will not be extensive, so the protections of the regulator
           should not be necessary
    */
  val wesRoutes: Route = concat(
    pathPrefix("ga4gh" / "wes" / "v1" / "runs") {
      path(Segment / "status") { possibleWorkflowId =>

        val response  = validateWorkflowId(possibleWorkflowId, serviceRegistryActor).flatMap(w => serviceRegistryActor.ask(GetStatus(w)).mapTo[MetadataServiceResponse])
        // WES can also return a 401 or a 403 but that requires user auth knowledge which Cromwell doesn't currently have
        onComplete(response) {
          case Success(s: StatusLookupResponse) =>
            val wesState = WesState.fromCromwellStatus(s.status)
            complete(WesRunStatus(s.workflowId.toString, wesState))
          case Success(r: FailedMetadataResponse) => r.reason.errorRequest(StatusCodes.InternalServerError)
          case Failure(e: UnrecognizedWorkflowException) => complete(NotFoundError)
          case Failure(e) => complete(WesErrorResponse(e.getMessage, StatusCodes.InternalServerError.intValue))
        }
      }
    }
  )
}

object WesRouteSupport {
  val NotFoundError = WesErrorResponse("The requested workflow run wasn't found", StatusCodes.NotFound.intValue)
}