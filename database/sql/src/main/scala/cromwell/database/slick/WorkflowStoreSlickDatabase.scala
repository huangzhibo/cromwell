package cromwell.database.slick

import java.sql.Timestamp

import cats.instances.future._
import cats.syntax.functor._
import cromwell.database.sql.WorkflowStoreSqlDatabase
import cromwell.database.sql.tables.WorkflowStoreEntry

import scala.concurrent.{ExecutionContext, Future}

trait WorkflowStoreSlickDatabase extends WorkflowStoreSqlDatabase {
  this: EngineSlickDatabase =>

  import dataAccess.driver.api._

  override def setStateToState(fromWorkflowState: String, toWorkflowState: String)
                              (implicit ec: ExecutionContext): Future[Unit] = {
    val action = dataAccess
      .workflowStateForWorkflowState(fromWorkflowState)
      .update(toWorkflowState)

    runTransaction(action).void
  }

  /**
    * Set the workflow Id to Aborting state.
    *
    * @param workflowExecutionUuid  Id to update or delete.
    * @param workflowStateToDelete  Delete rows with this state.
    * @param workflowStateForUpdate Update other rows to this state.
    * @return Number of rows deleted, plus Some(heartbeatTimestampIsEmpty) if the workflow exists in the store, where
    *         heartbeatTimestampIsEmpty is true if the workflow's heartbeat timestamp is empty, and None if the workflow
    *         does not exist.
    */
  override def deleteOrUpdateWorkflowToState(workflowExecutionUuid: String,
                                             workflowStateToDelete: String,
                                             workflowStateForUpdate: String)
                                            (implicit ec: ExecutionContext): Future[(Int, Option[Boolean])] = {
    val action =  for {
      restarted <- dataAccess.heartbeatClearedForWorkflowId(workflowExecutionUuid).result.headOption
      deleted <- dataAccess
        .workflowStateForWorkflowExecutionUUidAndWorkflowState((workflowExecutionUuid, workflowStateToDelete))
        .delete
      _ <- dataAccess.workflowStateForWorkflowExecutionUUid(workflowExecutionUuid).update(workflowStateForUpdate)
    } yield (deleted, restarted)
    
    runTransaction(action)
  }

  override def addWorkflowStoreEntries(workflowStoreEntries: Iterable[WorkflowStoreEntry])
                                      (implicit ec: ExecutionContext): Future[Unit] = {
    val action = dataAccess.workflowStoreEntryIdsAutoInc ++= workflowStoreEntries
    runTransaction(action).void
  }

  override def fetchWorkflowsInState(limit: Int,
                                     cromwellId: String,
                                     heartbeatTimestampStart: Timestamp,
                                     heartbeatTimestampTo: Timestamp,
                                     workflowStateFrom: String,
                                     workflowStateTo: String,
                                     workflowStateExcluded: String)
                                    (implicit ec: ExecutionContext): Future[Seq[WorkflowStoreEntry]] = {
    val action = for {
      workflowStoreEntries <- dataAccess.fetchStartableWorkflows(
        (limit.toLong, heartbeatTimestampStart, workflowStateExcluded)
      ).result
      _ <- DBIO.sequence(
        workflowStoreEntries map updateForFetched(cromwellId, heartbeatTimestampTo, workflowStateFrom, workflowStateTo)
      )
    } yield workflowStoreEntries

    runTransaction(action)
  }

  override def writeWorkflowHeartbeats(workflowExecutionUuids: Set[String],
                                       heartbeatTimestampOption: Option[Timestamp])
                                      (implicit ec: ExecutionContext): Future[Int] = {
    // Return the count of heartbeats written. This could legitimately be less than the size of the `workflowExecutionUuids`
    // List if any of those workflows completed and their workflow store entries were removed.
    val action = for {
      counts <- DBIO.sequence(workflowExecutionUuids.toList map { workflowExecutionUuid =>
        dataAccess.heartbeatForWorkflowStoreEntry(workflowExecutionUuid).update(heartbeatTimestampOption)
      })
    } yield counts.sum
    runTransaction(action)
  }

  override def releaseWorkflowStoreEntries(cromwellId: String)(implicit ec: ExecutionContext): Future[Unit] = {
    val action = dataAccess.releaseWorkflowStoreEntries(cromwellId).update((None, None))
    runTransaction(action).void
  }

  private def updateForFetched(cromwellId: String,
                               heartbeatTimestampTo: Timestamp,
                               workflowStateFrom: String,
                               workflowStateTo: String)
                              (workflowStoreEntry: WorkflowStoreEntry)
                              (implicit ec: ExecutionContext): DBIO[Unit] = {
    val workflowExecutionUuid = workflowStoreEntry.workflowExecutionUuid
    val updateState = workflowStoreEntry.workflowState match {
        // Submitted workflows become running when fetched
      case matched if matched == workflowStateFrom => workflowStateTo
        // Running or Aborting stay as is
      case other => other
    }

    for {
      // When fetched, the heartbeat timestamp is set to now so we don't pick it up next time.
      updateCount <- dataAccess
        .workflowStoreFieldsForPickup(workflowExecutionUuid)
        .update((updateState, Option(cromwellId), Option(heartbeatTimestampTo)))
      _ <- assertUpdateCount(
        s"Update $workflowExecutionUuid to $updateState, heartbeat timestamp to $heartbeatTimestampTo",
        updateCount,
        1
      )
    } yield ()
  }

  override def removeWorkflowStoreEntry(workflowExecutionUuid: String)(implicit ec: ExecutionContext): Future[Int] = {
    val action = dataAccess.workflowStoreEntriesForWorkflowExecutionUuid(workflowExecutionUuid).delete
    runTransaction(action)
  }

  override def workflowStateCounts(implicit ec: ExecutionContext): Future[Map[String, Int]] = {
    val action = dataAccess.workflowStoreStats.result
    runTransaction(action) map { _.toMap }
  }

  override def setOnHoldToSubmitted(workflowExecutionUuid: String,
                                    fromWorkflowState: String,
                                    toWorkflowState: String)
                                   (implicit ec: ExecutionContext): Future[Int] = {
    val action = for {
      updated <- dataAccess
        .workflowStateForWorkflowExecutionUUidAndWorkflowState((workflowExecutionUuid, fromWorkflowState))
        .update(toWorkflowState)
    } yield updated

    runTransaction(action)
  }
}
